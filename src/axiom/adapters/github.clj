(ns axiom.adapters.github
  "The ONLY namespace that touches the network (spec 0004 R8).

   Read-only GitHub provider observation behind the pure
   `axiom.github` port: PR identity, the complete changed-file list,
   workflow runs with jobs, attempts and matrix axes, reviews and
   artifact references. Every map produced here is validated with
   `axiom.github/validate-observation!` before it is returned.

   GET requests only. There is exactly one request constructor,
   `build-get-request`, and it always issues GET: there is no other
   constructor, so mutation of provider state is structurally
   impossible (grep the namespace for `.GET` to verify the single
   construction site). The JDK `java.net.http.HttpClient` is the only
   HTTP machinery; no new production dependencies.

   Provider payloads are untrusted text until validated: every
   required field is checked for presence and shape, and violations
   are operational failures naming the offending field — never
   silently normalized. Unknown check conclusions map to `:unknown`
   (never success); unknown file statuses, PR states and review
   states are malformed payloads.

   Pagination follows the provider's `Link` `rel=\"next\"` headers to
   the end of every collection; each collection carries
   `{:pagination/complete true :pagination/pages n}`. A page that
   fails after bounded retries makes the whole observation
   `:observation/incomplete` naming the failing collection and page,
   with the retry bound named in the reason — never a silently
   partial list.

   Retries: HTTP 429 and transient 5xx are retried a bounded number of
   times with backoff, honoring `Retry-After` and
   `X-RateLimit-Reset` when present. 401/403/404 (and any other
   unexpected status) are terminal operational failures naming the
   status and the resource.

   ETag cache: bounded, keyed by [owner repo resource url], storing
   the ETag, the response body and the base/head SHAs the entry was
   recorded under. Entries are invalidated when the observed base or
   head SHA moves; a 304 answered for a stale entry is an operational
   failure, not a silent reuse.

   Identity (spec T3): the credential is resolved out-of-band only —
   `AXIOM_GITHUB_TOKEN` from the environment or `--token-file PATH`
   (a raw token is never accepted as an argument and never logged).
   The token identity is resolved once per observation via the
   provider's `/user` endpoint and recorded; authenticated
   observations are marked `:trust/provider-authenticated`,
   anonymous ones `:trust/provider-observed`. Workflow/run identity
   is checked for internal consistency (unique run IDs, unique
   ascending attempt numbers, job/run association, run and job
   conclusions agreeing with their selected attempts);
   inconsistencies are named operational failures.

   The fetch function is injectable (`:fetch-fn`), so tests run the
   full pagination/retry/identity logic against synthetic fixtures
   with zero network. Fixture format: an EDN map keyed by request
   URL to response maps
   `{:fetch/status int :fetch/headers {lower-name [values]} :fetch/body edn}`;
   a vector of response maps at one URL is consumed in order, which
   models retry sequences (e.g. 429 then 200). `fixture-fetch` builds
   such a fetch fn. All fixtures are synthetic: invented `synth-*`
   repositories, SHAs and logins — no real identities, no live
   credentials."

  (:require [axiom.github :as gh]
            [axiom.model :as model]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.net URI)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse$BodyHandlers)
           (java.time Duration Instant)
           (java.time.format DateTimeFormatter)))

;; ------------------------------------------------------------------
;; Configuration (declared; recorded in observation provenance)

(def default-config
  {:api-base "https://api.github.com"
   :api-version "2022-11-28"
   :user-agent "axiom-github-observer/0.1.0-offline"
   :connect-timeout-ms 5000
   :request-timeout-ms 30000
   :max-attempts 5
   :base-backoff-ms 1000
   :max-wait-ms 60000
   :etag-cache-size 128
   :per-page 100})

;; ------------------------------------------------------------------
;; Errors

(defn- operational!
  "An operational failure: the provider, the network or the local
   environment failed in a named way. Carries :axiom/error
   :operational (the CLI maps it to exit 5). Never thrown for
   malformed caller input — that is :invalid."
  [message data]
  (throw (ex-info message (assoc data :axiom/error :operational))))

(defn- retry-exhausted!
  [resource attempts status]
  (throw (ex-info (str "Retry budget exhausted for " resource
                       ": " attempts " attempts, last status " status)
                  {:axiom/error :retry-exhausted
                   :resource resource
                   :attempts attempts
                   :status status})))

;; ------------------------------------------------------------------
;; Minimal JSON reader (pure; only used by the real network fetch —
;; injected fixture fetches return already-parsed EDN)

(defn- json-parse
  "Reads a JSON text into Clojure data with string keys. Throws an
   operational failure on malformed input (the provider sent bytes
   that are not JSON)."
  [^String s]
  (let [n (count s)
        pos (atom 0)
        fail #(operational! "Response body is not valid JSON"
                            {:at @pos :detail (str %)})]
    (letfn [(peek-char [] (when (< @pos n) (.charAt s ^int @pos)))
            (next-char [] (let [c (peek-char)] (swap! pos inc) c))
            (skip-ws [] (loop [] (let [c (peek-char)]
                                   (when (and c (Character/isWhitespace ^char c))
                                     (next-char) (recur)))))
            (expect [c] (skip-ws)
              (if (= c (peek-char)) (next-char)
                  (fail (str "expected " c))))
            (parse-string []
              (expect \")
              (let [sb (StringBuilder.)]
                (loop []
                  (let [c (next-char)]
                    (cond
                      (nil? c) (fail "unterminated string")
                      (= c \") (str sb)
                      (= c \\)
                      (let [e (next-char)]
                        (case e
                          \" (.append sb \")
                          \\ (.append sb \\)
                          \/ (.append sb \/)
                          \b (.append sb \backspace)
                          \f (.append sb \formfeed)
                          \n (.append sb \newline)
                          \r (.append sb \return)
                          \t (.append sb \tab)
                          \u (let [hex (apply str (repeatedly 4 next-char))
                                   cp (try (Integer/parseInt hex 16)
                                           (catch NumberFormatException _
                                             (fail (str "bad \\u escape: " hex))))]
                               (if (Character/isHighSurrogate (char cp))
                                 (do (expect \\) (expect \u)
                                     (let [hex2 (apply str (repeatedly 4 next-char))
                                           lo (try (Integer/parseInt hex2 16)
                                                   (catch NumberFormatException _
                                                     (fail (str "bad low surrogate: " hex2))))]
                                       (.appendCodePoint sb (Character/toCodePoint (char cp) (char lo)))))
                                 (.append sb (char cp))))
                          (fail (str "bad escape: \\" e)))
                        (recur))
                      :else (do (.append sb c) (recur)))))))
            (parse-number []
              (skip-ws)
              (let [m (re-find #"^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"
                               (subs s @pos))]
                (when-not m (fail "expected number"))
                (swap! pos + (count m))
                (if (re-find #"[\.eE]" m)
                  (Double/parseDouble m)
                  (try (Long/parseLong m)
                       (catch NumberFormatException _ (fail (str "bad number: " m)))))))
            (parse-literal [text value]
              (skip-ws)
              (if (str/starts-with? (subs s @pos) text)
                (do (swap! pos + (count text)) value)
                (fail (str "expected " text))))
            (parse-value []
              (skip-ws)
              (let [c (peek-char)]
                (cond
                  (nil? c) (fail "unexpected end of input")
                  (= c \") (parse-string)
                  (= c \{) (parse-object)
                  (= c \[) (parse-array)
                  (= c \t) (parse-literal "true" true)
                  (= c \f) (parse-literal "false" false)
                  (= c \n) (parse-literal "null" nil)
                  (or (= c \-) (Character/isDigit ^char c)) (parse-number)
                  :else (fail (str "unexpected character: " c)))))
            (parse-array []
              (expect \[)
              (loop [items []]
                (skip-ws)
                (if (= \] (peek-char))
                  (do (next-char) items)
                  (let [v (parse-value)]
                    (skip-ws)
                    (let [c (peek-char)]
                      (cond (= c \,) (do (next-char) (recur (conj items v)))
                            (= c \]) (do (next-char) (conj items v))
                            :else (fail "expected , or ] in array")))))))
            (parse-object []
              (expect \{)
              (loop [obj {}]
                (skip-ws)
                (if (= \} (peek-char))
                  (do (next-char) obj)
                  (let [k (parse-string)]
                    (skip-ws) (expect \:)
                    (let [v (parse-value)]
                      (skip-ws)
                      (let [c (peek-char)]
                        (cond (= c \,) (do (next-char) (recur (assoc obj k v)))
                              (= c \}) (do (next-char) (assoc obj k v))
                              :else (fail "expected , or } in object"))))))))]
      (let [v (parse-value)]
        (skip-ws)
        (when (< @pos n) (fail "trailing characters after JSON value"))
        v))))

;; ------------------------------------------------------------------
;; HTTP: the single GET-only request path

(defn- build-get-request
  "The ONLY request constructor in this namespace: it always issues
   GET. It is the sole place where an HttpRequest is built, so no
   code path in the adapter can mutate provider state."
  [url headers timeout-ms]
  (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofMillis timeout-ms))
                    (.GET))]
    (doseq [[k v] headers]
      (.header builder ^String k ^String v))
    (.build builder)))

(defn- default-headers
  [config]
  {"User-Agent" (:user-agent config)
   "Accept" "application/vnd.github+json"
   "X-GitHub-Api-Version" (:api-version config)})

(defn- http-fetch-fn
  "The real network fetch: JDK HttpClient, GET only, declared
   timeouts. Returns {:fetch/status :fetch/headers :fetch/body} with
   the body JSON-parsed (nil for an empty body, e.g. 304)."
  [config]
  (let [client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofMillis (:connect-timeout-ms config)))
                   (.followRedirects HttpClient$Redirect/NEVER)
                   (.build))]
    (fn [{:fetch/keys [url headers]}]
      (let [request (build-get-request url (merge (default-headers config) headers)
                                       (:request-timeout-ms config))
            response (.send client request (HttpResponse$BodyHandlers/ofString))
            header-map (into {} (map (fn [[k v]] [(str/lower-case k) (vec v)])
                                     (.map (.headers response))))
            body-text (.body response)]
        {:fetch/status (.statusCode response)
         :fetch/headers header-map
         :fetch/body (when (seq body-text) (json-parse body-text))}))))

;; ------------------------------------------------------------------
;; Injectable fixture fetch (tests; zero network)

(defn fixture-fetch
  "Builds an injectable fetch fn from EDN fixture snapshots keyed by
   request URL:

     {url {:fetch/status 200
           :fetch/headers {\"link\" [\"<...> ; rel=\\\"next\\\"\"]}
           :fetch/body {...}}}

   A vector of response maps at one URL is consumed in order, which
   models retry sequences (429 then 200, 503 then 503 then 200).
   Header names must already be lower-case vectors, matching the real
   fetch's contract.

   Returns {:fetch/fn f :fetch/requested (atom [urls...])}; requests
   for an unfixed URL are operational failures (the test's fixture
   set is incomplete, not the adapter's logic)."
  [fixtures]
  (let [remaining (atom (into {} (map (fn [[url resp]]
                                        [url (if (sequential? resp) (vec resp) [resp])])
                                      fixtures)))
        requested (atom [])]
    {:fetch/fn (fn [{:fetch/keys [url]}]
                 (swap! requested conj url)
                 (let [queue (get @remaining url)]
                   (when (empty? queue)
                     (operational! "No fixture response for request URL"
                                   {:url url}))
                   (swap! remaining update url rest)
                   (first queue)))
     :fetch/requested requested}))

;; ------------------------------------------------------------------
;; Retries, backoff, rate limits

(defn- header-first
  "First value of a (lower-cased) response header, or nil."
  [headers name]
  (first (get headers name)))

(defn- parse-long-header
  [headers name]
  (when-let [v (header-first headers name)]
    (try (Long/parseLong (str/trim v))
         (catch NumberFormatException _ nil))))

(defn- retry-after-ms
  "Honors Retry-After: delta-seconds, or an HTTP date. Returns nil
   when the header is absent or unparseable."
  [headers clock-ms]
  (when-let [v (header-first headers "retry-after")]
    (let [v (str/trim v)]
      (or (try (* 1000 (Long/parseLong v))
               (catch NumberFormatException _ nil))
          (try (let [reset-at (.toEpochMilli
                               (Instant/from (.parse DateTimeFormatter/RFC_1123_DATE_TIME v)))]
                 (max 0 (- reset-at (clock-ms))))
               (catch Exception _ nil))))))

(defn- rate-limit-reset-ms
  "Honors X-RateLimit-Reset (epoch seconds): milliseconds until the
   window resets, floored at 0. Nil when absent or unparseable."
  [headers clock-ms]
  (when-let [reset (parse-long-header headers "x-ratelimit-reset")]
    (max 0 (- (* 1000 reset) (clock-ms)))))

(defn- backoff-ms
  "Wait before the next attempt: the largest of the honored
   Retry-After / X-RateLimit-Reset waits and the exponential backoff
   (base * 2^(attempt-1)), capped at max-wait-ms. Deterministic — no
   jitter — so fixture tests can assert exact waits."
  [config attempt headers clock-ms]
  (let [header-wait (max (or (retry-after-ms headers clock-ms) 0)
                         (or (rate-limit-reset-ms headers clock-ms) 0))
        exponential (min (:max-wait-ms config)
                         (* (:base-backoff-ms config)
                            (long (Math/pow 2 (dec attempt)))))]
    (min (:max-wait-ms config) (max header-wait exponential))))

(defn- retryable-status? [status]
  (or (= 429 status) (<= 500 status 599)))

(defn- terminal-status? [status]
  (or (= 401 status) (= 403 status) (= 404 status)))

(defn- fetch-with-retries
  "One GET with bounded retries. Returns {:ok response} on 200 or
   {:not-modified response} on 304. 401/403/404 (and any other
   unexpected status) throw terminal operational failures naming the
   status and the resource. 429/transient 5xx are retried up to
   max-attempts with backoff; exhaustion throws :retry-exhausted
   (the pagination loop turns that into an :observation/incomplete
   observation naming the collection, page and bound)."
  [fetch-fn sleep-fn clock-ms config resource url headers]
  (loop [attempt 1]
    (let [{:fetch/keys [status] :as response} (fetch-fn {:fetch/url url :fetch/headers headers})]
      (cond
        (= 200 status) {:ok response}
        (= 304 status) {:not-modified response}

        (terminal-status? status)
        (operational! (str "GitHub API terminal failure: HTTP " status
                           " for " resource " (" url ")")
                      {:status status :resource resource :url url})

        (retryable-status? status)
        (if (>= attempt (:max-attempts config))
          (retry-exhausted! resource (:max-attempts config) status)
          (do (sleep-fn (backoff-ms config attempt (:fetch/headers response) clock-ms))
              (recur (inc attempt))))

        :else
        (operational! (str "GitHub API unexpected status: HTTP " status
                           " for " resource " (" url ")")
                      {:status status :resource resource :url url})))))

;; ------------------------------------------------------------------
;; Link pagination

(def ^:private link-entry-re #"<([^>]+)>\s*;\s*rel=\"([^\"]+)\"")

(defn- next-link
  "Extracts the rel=\"next\" URL from a Link response header, or nil
   when there is no next page."
  [headers]
  (let [link (str/join ", " (get headers "link" []))]
    (some (fn [[_ url rel]] (when (= "next" rel) url))
          (re-seq link-entry-re link))))

(defn- extract-items
  "Pulls the item sequence out of a paginated response body: either a
   bare JSON array, or a map holding the items under items-key.
   Anything else is a malformed provider payload naming the field."
  [body items-key resource]
  (cond
    (sequential? body) (vec body)
    (and (map? body) (some? items-key) (sequential? (get body items-key)))
    (vec (get body items-key))
    :else (operational! (str "Malformed provider payload for " resource
                             ": expected a JSON array or an object with \""
                             items-key "\"")
                        {:resource resource :field (or items-key "body")})))

;; ------------------------------------------------------------------
;; Bounded ETag cache

(defn- cache-get [cache key] (get-in @cache [:entries key]))

(defn- cache-invalidate [cache key]
  (swap! cache (fn [c] (-> c
                           (update :entries dissoc key)
                           (update :order (fn [order] (vec (remove #(= % key) order))))))))

(defn- cache-put!
  "Stores an entry, evicting the oldest when the bound is reached."
  [cache max-entries key entry]
  (swap! cache
         (fn [{:keys [entries order] :as c}]
           (let [entries (assoc entries key entry)
                 order (conj (vec (remove #(= % key) order)) key)]
             (if (> (count order) max-entries)
               (let [evict (first order)]
                 {:entries (dissoc entries evict) :order (vec (rest order))})
               {:entries entries :order order})))))

(defn- new-etag-cache [] (atom {:entries {} :order []}))

;; ------------------------------------------------------------------
;; Credential resolution (spec T3: out-of-band only)

(defn- resolve-credential
  "Resolves the credential out-of-band: :token-file (a path whose
   contents are read and trimmed) wins over the AXIOM_GITHUB_TOKEN
   environment variable. A raw token is never accepted as an
   argument, and the token value never appears in errors, logs or
   provenance. An unreadable or empty token file is :invalid (bad
   caller input); a missing credential means anonymous observation."
  [{:keys [token-file env]}]
  (cond
    (some? token-file)
    (let [file (io/file ^String token-file)]
      (when-not (.isFile file)
        (model/invalid! "Token file is not readable" {:token-file token-file}))
      (let [token (try (str/trim (slurp file :encoding "UTF-8"))
                       (catch Exception _
                         (model/invalid! "Token file is not readable"
                                         {:token-file token-file})))]
        (when (str/blank? token)
          (model/invalid! "Token file is empty" {:token-file token-file}))
        {:credential/kind :token-file :token token}))

    :else
    (let [token (get env "AXIOM_GITHUB_TOKEN")]
      (if (str/blank? token)
        {:credential/kind :anonymous}
        {:credential/kind :env-token :token (str/trim token)}))))

;; ------------------------------------------------------------------
;; Strict provider-payload accessors (untrusted text until validated)

(defn- field!
  "Required field of a provider object: present and satisfying pred,
   else an operational failure naming the field. Never silently
   normalized."
  [obj field pred resource]
  (let [v (get obj field ::missing)]
    (when (= ::missing v)
      (operational! (str "Malformed provider payload for " resource
                         ": missing required field \"" field "\"")
                    {:resource resource :field field}))
    (when-not (pred v)
      (operational! (str "Malformed provider payload for " resource
                         ": invalid field \"" field "\"")
                    {:resource resource :field field}))
    v))

(defn- non-blank? [s] (and (string? s) (not (str/blank? s))))
(defn- bool? [b] (boolean? b))
(defn- positive-int? [n] (and (integer? n) (pos? n)))
(defn- sha40? [s] (and (string? s) (boolean (re-matches #"[0-9a-f]{40}" s))))
(defn- login-like? [s]
  (and (string? s)
       (boolean (re-matches #"[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?" s))))
(defn- digest-like? [d]
  (and (string? d) (boolean (re-matches #"sha256:[0-9a-f]{64}" d))))

(defn- instant->epoch!
  "Parses an ISO-8601 timestamp into epoch seconds; unparseable
   values are operational failures naming the field."
  [v field resource]
  (when (some? v)
    (when-not (string? v)
      (operational! (str "Malformed provider payload for " resource
                         ": invalid field \"" field "\" (expected ISO-8601 timestamp)")
                    {:resource resource :field field}))
    (try (.getEpochSecond (Instant/parse v))
         (catch Exception _
           (operational! (str "Malformed provider payload for " resource
                              ": invalid field \"" field "\" (expected ISO-8601 timestamp)")
                         {:resource resource :field field})))))

(defn- actor-login!
  "Normalizes the actor identity: the provider may give an object
   with \"login\" or a bare login string. Missing or blank is a
   malformed payload naming the field."
  [obj resource]
  (let [actor (field! obj "actor" some? resource)
        login (if (map? actor) (get actor "login") actor)]
    (when-not (non-blank? login)
      (operational! (str "Malformed provider payload for " resource
                         ": invalid field \"actor\" (expected a login)")
                    {:resource resource :field "actor"}))
    login))

;; ------------------------------------------------------------------
;; Request context plumbing

(defn- auth-headers
  "Per-request headers: Authorization only when a token credential
   was resolved. The token value travels in the header alone — never
   in URLs, errors or provenance."
  [ctx]
  (if-let [token (:token (:credential ctx))]
    {"Authorization" (str "Bearer " token)}
    {}))

(defn- record-response!
  "Records provenance for one response: the URL (in request order),
   its ETag (nil when absent), and the latest rate-limit state."
  [ctx url {:fetch/keys [headers]}]
  (let [{:keys [provenance rate-limit]} ctx]
    ;; The same URL may be requested several times (retries,
    ;; conditional revalidation): provenance records each distinct
    ;; URL once, with its latest ETag.
    (swap! provenance update :api/urls
           (fn [urls] (if (some #(= % url) urls) urls (conj urls url))))
    (swap! provenance assoc-in [:api/etags url] (header-first headers "etag"))
    (let [limit (parse-long-header headers "x-ratelimit-limit")
          remaining (parse-long-header headers "x-ratelimit-remaining")
          reset (parse-long-header headers "x-ratelimit-reset")]
      (when (or limit remaining reset)
        (reset! rate-limit {:rate-limit/limit limit
                            :rate-limit/remaining remaining
                            :rate-limit/reset reset})))))

(defn- with-query
  [url params]
  (str url "?" (str/join "&" (map (fn [[k v]] (str k "=" v)) params))))

;; Token identity resolution (once per observation)

(defn- resolve-token-identity!
  "Resolves the token identity via the provider's /user endpoint.
   A 401 means the credential is rejected (operational, named); a
   malformed identity payload is operational too. The login is
   validated against the account-name shape here so a bad provider
   value never reaches the port as an :invalid surprise. The endpoint
   URL is derived from the configured API base so synthetic fixture
   configurations stay consistent."
  [ctx]
  (let [{:keys [fetch-fn sleep-fn clock-ms config credential]} ctx
        headers (auth-headers ctx)
        user-url (str (:api-base config) "/user")
        {:keys [ok]} (fetch-with-retries fetch-fn sleep-fn clock-ms config
                                         "token identity" user-url headers)
        body (:fetch/body ok)
        resource "token identity"]
    (record-response! ctx user-url ok)
    (when-not (map? body)
      (operational! "Malformed provider payload for token identity: expected an object"
                    {:resource resource}))
    (let [login (field! body "login" login-like? resource)]
      {:github/login login})))

;; ------------------------------------------------------------------
(defn- fetch-page!
  "One collection page with ETag handling. Returns {:ok response}
   on 200, {:cached entry} on a valid 304. A 304 for a missing or
   stale cache entry (base/head moved since the entry was recorded)
   is an operational failure — a stale cache hit is never silently
   reused."
  [ctx resource cache-subkey url]
  (let [{:keys [fetch-fn sleep-fn clock-ms config etag-cache owner repo base head]} ctx
        key [owner repo resource cache-subkey]
        entry (cache-get etag-cache key)
        ;; An entry recorded under different base/head SHAs is stale:
        ;; invalidate it and never send its ETag.
        entry (when (and (some? entry)
                         (= (:base entry) base)
                         (= (:head entry) head))
                entry)
        _ (when (and (some? (cache-get etag-cache key)) (nil? entry))
            (cache-invalidate etag-cache key))
        headers (cond-> (auth-headers ctx)
                  (some? entry) (assoc "If-None-Match" (:etag entry)))
        result (fetch-with-retries fetch-fn sleep-fn clock-ms config
                                   resource url headers)]
    (record-response! ctx url (or (:ok result) (:not-modified result)))
    (cond
      (:ok result)
      (let [response (:ok result)
            etag (header-first (:fetch/headers response) "etag")]
        (when (some? etag)
          (cache-put! etag-cache (:etag-cache-size config) key
                       {:etag etag
                        :body (:fetch/body response)
                        :base base
                        :head head
                        ;; The cached next-page link, so a later 304
                        ;; hit can continue the pagination chain
                        ;; without re-fetching known pages.
                        :next-url (next-link (:fetch/headers response))}))
        {:ok response})

      (:not-modified result)
      (let [live (cache-get etag-cache key)
            ;; Prefer the 304 response's own Link header; fall back to
            ;; the next URL recorded with the cached entry (304 means
            ;; the cached representation — including its pagination
            ;; chain — is still current).
            next-url (or (next-link (:fetch/headers (:not-modified result)))
                         (:next-url live))]
        (if (and (some? live)
                 (= (:base live) base)
                 (= (:head live) head))
          {:cached (assoc live :next-url next-url)}
          (operational! (str "Stale ETag cache hit for " resource
                             ": the 304 response names an entry recorded under "
                             "different base/head SHAs")
                        {:resource resource :url url}))))))

(defn- paginate!
  "Fetches a collection to the end following Link rel=\"next\".
   Returns {:pagination/complete true :pagination/pages n :items [...]
   :urls [...]} on success, or {:pagination/complete false
   :pagination/pages n :items [] :urls [...] :failing-step
   {:collection c :page p :reason r :github/run-id ...}} when a page
   fails after bounded retries — never a silently partial list."
  [ctx collection-key items-key resource url0 & [extra]]
  (loop [url url0 page 1 items [] urls []]
    (let [outcome (try
                    (fetch-page! ctx resource url url)
                    (catch clojure.lang.ExceptionInfo e
                      (if (= :retry-exhausted (:axiom/error (ex-data e)))
                        {:exhausted e}
                        (throw e))))
          failing (fn [reason]
                    {:pagination/complete false
                     :pagination/pages page
                     :items []
                     :urls (conj urls url)
                     :failing-step (merge {:collection collection-key
                                           :page page
                                           :reason reason}
                                          extra)})]
      (cond
        (:exhausted outcome)
        (let [{:keys [attempts status]} (ex-data (:exhausted outcome))]
          (failing (str "retry budget exhausted after " attempts
                        " attempts (last status " status ")")))

        (:cached outcome)
        ;; A cached page is a complete page: its body was recorded as
        ;; one page under the current base/head. Continue the cached
        ;; pagination chain to the end.
        (let [body (:body (:cached outcome))
              page-items (extract-items body items-key resource)
              items* (into items page-items)
              urls* (conj urls url)
              next-url (:next-url (:cached outcome))]
          (if (some? next-url)
            (recur next-url (inc page) items* urls*)
            {:pagination/complete true
             :pagination/pages page
             :items items*
             :urls urls*}))

        :else
        (let [response (:ok outcome)
              body (:fetch/body response)
              page-items (extract-items body items-key resource)
              next-url (next-link (:fetch/headers response))
              urls* (conj urls url)]
          (if (some? next-url)
            (recur next-url (inc page) (into items page-items) urls*)
            {:pagination/complete true
             :pagination/pages page
             :items (into items page-items)
             :urls urls*}))))))

;; ------------------------------------------------------------------
;; Provider payload parsing (untrusted → port maps)

(defn- repo-like?
  [s]
  (and (string? s)
       (boolean (re-matches #"[A-Za-z0-9](?:[A-Za-z0-9._-]{0,99})?" s))))

(defn- incomplete!
  "Signals a pagination failure up to observe!, which turns it into
   an :observation/incomplete observation naming the collection and
   page. extra is merged into the ex-data (e.g. :partial-runs for a
   nested :jobs failure, where the port requires the failing run to
   stay in the record with its job list marked incomplete). The
   failing step is normalized to the pure-port shape: :github/run-id
   is always present (nil except for jobs failures)."
  [failing-step & [extra]]
  (throw (ex-info "Collection pagination failed"
                  (merge {:axiom/error :incomplete-observation
                          :failing-step (assoc failing-step
                                               :github/run-id (get failing-step :github/run-id))}
                         extra))))

(defn- nest-failing-step
  "Rewrites a nested paginate! failure (e.g. a run's attempts) into
   the observation-level failing step vocabulary (:runs or :jobs),
   keeping the nested collection and page named in the reason."
  [pg outer-collection extra reason-prefix]
  (let [step (:failing-step pg)]
    (incomplete! (merge {:collection outer-collection
                         :page (:page step)
                         :reason (str reason-prefix ": " (:reason step))}
                        extra))))

(defn- fetch-single!
  "Fetches a single (non-paginated) resource. Retry exhaustion here
   is an operational failure naming the bound — there is no
   collection to mark incomplete."
  [ctx resource cache-subkey url]
  (let [outcome (try
                  (fetch-page! ctx resource cache-subkey url)
                  (catch clojure.lang.ExceptionInfo e
                    (if (= :retry-exhausted (:axiom/error (ex-data e)))
                      (let [{:keys [attempts status]} (ex-data e)]
                        (operational!
                         (str "Retry budget exhausted for " resource
                              ": " attempts " attempts (last status " status ")")
                         {:resource resource :attempts attempts :status status}))
                      (throw e))))]
    (if (:ok outcome)
      (:fetch/body (:ok outcome))
      (:body (:cached outcome)))))

(defn- parse-pr!
  "Parses the pull-request object into the port's pr/record plus the
   subject identities. Every identity field is checked; the payload
   PR number must match the requested one."
  [ctx body pr expected-number]
  (let [resource "pull request"]
    (when-not (map? body)
      (operational! "Malformed provider payload for pull request: expected an object"
                    {:resource resource}))
    (let [number (field! body "number" positive-int? resource)]
      (when (not= number expected-number)
        (operational! "PR identity mismatch: payload PR number differs from the requested PR"
                      {:resource resource :requested expected-number :observed number}))
      (let [title (field! body "title" non-blank? resource)
            state (field! body "state" string? resource)
            merged (field! body "merged" bool? resource)
            pr-state (or (gh/pr-state-from-provider
                          (cond (= "open" state) "open"
                                (and (= "closed" state) merged) "merged"
                                (= "closed" state) "closed"
                                :else nil))
                         (operational! (str "Malformed provider payload for pull request"
                                            ": invalid field \"state\"")
                                       {:resource resource :field "state"}))
            draft (field! body "draft" bool? resource)
            mergeable (field! body "mergeable" bool? resource)
            base-sha (field! (field! body "base" map? resource) "sha" sha40? resource)
            head-obj (field! body "head" map? resource)
            head-sha (field! head-obj "sha" sha40? resource)
            head-repo-obj (field! head-obj "repo" map? resource)
            head-owner (field! (field! head-repo-obj "owner" map? resource)
                               "login" login-like? resource)
            head-repo (field! head-repo-obj "name" repo-like? resource)]
        {:pr/record {:github/pr-title title
                     :github/pr-state pr-state
                     :github/draft? draft
                     :github/merge-state (gh/merge-state-from-provider
                                          (get body "merge_state_status"))
                     :github/mergeable? mergeable
                     :evidence/url pr}
         :git/base base-sha
         :git/head head-sha
         :github/head-repo {:github/owner head-owner
                            :github/repo head-repo}}))))

(defn- parse-change!
  "Parses one changed-file entry. An unknown provider file status is
   a malformed payload naming the field — never silently
   normalized."
  [file resource]
  (when-not (map? file)
    (operational! "Malformed provider payload for changed files: expected an object"
                  {:resource resource}))
  (let [status (field! file "status" string? resource)
        kind (or (gh/change-kind-from-provider-status status)
                 (operational! (str "Malformed provider payload for changed files"
                                    ": invalid field \"status\"")
                               {:resource resource :field "status"}))
        filename (field! file "filename" non-blank? resource)
        previous (get file "previous_filename")
        _ (when (some? previous)
            (when-not (non-blank? previous)
              (operational! "Malformed provider payload for changed files: invalid field \"previous_filename\""
                            {:resource resource :field "previous_filename"})))
        [old-path new-path] (case kind
                              :added [nil filename]
                              :removed [filename nil]
                              :modified [filename filename]
                              :renamed [previous filename])]
    (when (and (= :renamed kind) (nil? previous))
      (operational! "Malformed provider payload for changed files: renamed entry without \"previous_filename\""
                    {:resource resource :field "previous_filename"}))
    {:change/kind kind
     :path/kind :file
     :change/old-path old-path
     :change/new-path new-path
     :change/old-mode nil
     :change/new-mode nil
     :path/target nil
     :path/commit nil
     :path/unsafe-reason nil}))

(defn- parse-attempt-objects!
  "Parses attempt objects into port attempt maps, checking identity
   consistency: positive, unique attempt numbers in ascending order."
  [items resource]
  (let [attempts
        (mapv (fn [attempt]
                (when-not (map? attempt)
                  (operational! (str "Malformed provider payload for " resource
                                     ": expected an object")
                                {:resource resource}))
                {:github/attempt (field! attempt "attempt_number" positive-int? resource)
                 :github/conclusion (gh/conclusion-from-provider
                                     (field! attempt "conclusion" string? resource))
                 :evidence/url (field! attempt "url" non-blank? resource)})
              items)
        numbers (mapv :github/attempt attempts)]
    (when (not= (count numbers) (count (set numbers)))
      (operational! (str "Inconsistent identity for " resource ": duplicate attempt numbers")
                    {:resource resource}))
    (when (not= numbers (sort numbers))
      (operational! (str "Inconsistent identity for " resource
                         ": attempt numbers are not in ascending order")
                    {:resource resource}))
    (when (empty? attempts)
      (operational! (str "Malformed provider payload for " resource
                         ": no attempts enumerated")
                    {:resource resource}))
    attempts))

(defn- parse-job!
  "Parses one job object with its attempts, checking workflow/run
   identity consistency: the job's run association, the provider's
   stated latest attempt number against the enumerated attempts, and
   the job conclusion against the selected attempt's conclusion."
  [ctx run run-attempts job]
  (let [run-id (:github/run-id run)
        resource (str "run " run-id " jobs")]
    (when-not (map? job)
      (operational! (str "Malformed provider payload for " resource ": expected an object")
                    {:resource resource}))
    (let [job-id (field! job "id" positive-int? resource)
          job-name (field! job "name" non-blank? resource)
          job-resource (str "job " job-id)
          job-run-id (get job "run_id")]
      (when (and (some? job-run-id) (not= job-run-id run-id))
        (operational! (str "Inconsistent identity for " job-resource
                           ": job run association differs from the parent run")
                      {:resource job-resource :job-run-id job-run-id :run-id run-id}))
      (let [stated-conclusion (gh/conclusion-from-provider
                               (field! job "conclusion" string? resource))
            stated-attempt (field! job "run_attempt" positive-int? resource)
            matrix-obj (get job "matrix")
            _ (when (some? matrix-obj)
                (when-not (and (map? matrix-obj)
                               (every? (fn [[k v]] (and (non-blank? k) (non-blank? v)))
                                       matrix-obj))
                  (operational! (str "Malformed provider payload for " job-resource
                                     ": invalid field \"matrix\"")
                                {:resource job-resource :field "matrix"})))
            attempts-url (field! job "attempts_url" non-blank? resource)
            job-url (field! job "url" non-blank? resource)
            attempts-pg (paginate! ctx :job-attempts nil
                                   (str "job " job-id " attempts") attempts-url)]
        (when-not (:pagination/complete attempts-pg)
          ;; A job's attempts failing to paginate is a :jobs failure
          ;; for the parent run (the port has no :attempts failing
          ;; collection): parse-run! catches this, keeps the run with
          ;; its job list marked incomplete, and names the job in the
          ;; reason.
          (throw (ex-info "Job attempts pagination failed"
                          {:axiom/error :job-attempts-failed
                           :failing-step (:failing-step attempts-pg)
                           :job-id job-id})))
        (let [attempts (parse-attempt-objects! (:items attempts-pg) (str "job " job-id " attempts"))
              selected (apply max (map :github/attempt attempts))
              run-conclusions (into {} (map (juxt :github/attempt :github/conclusion)
                                            run-attempts))]
          (when (not= stated-attempt selected)
            (operational! (str "Inconsistent identity for " job-resource
                               ": provider's latest attempt number differs from the enumerated attempts")
                          {:resource job-resource :stated stated-attempt :enumerated selected}))
          ;; A job's attempt numbers must correspond to the run's
          ;; attempt numbers: they name the same re-run of the
          ;; workflow. (Conclusions are NOT required to agree — a job
          ;; can fail while the run's aggregate conclusion differs
          ;; because of other jobs.)
          (doseq [{:keys [github/attempt]} attempts]
            (when-not (contains? run-conclusions attempt)
              (operational! (str "Inconsistent identity for " job-resource
                                 ": job attempt has no matching run attempt")
                            {:resource job-resource :attempt attempt})))
          (let [selected-conclusion (:github/conclusion
                                     (first (filter #(= selected (:github/attempt %)) attempts)))]
            (when (not= stated-conclusion selected-conclusion)
              (operational! (str "Inconsistent identity for " job-resource
                                 ": job conclusion differs from the selected attempt's conclusion")
                            {:resource job-resource})))
          {:github/job-id job-id
           :github/job-name job-name
           :github/conclusion stated-conclusion
           :github/selected-attempt selected
           :github/attempts attempts
           :matrix/axes (into {} (map (fn [[k v]] [(keyword k) v]))
                              (or matrix-obj {}))
           :evidence/url job-url})))))

(defn- parse-run!
  "Parses one workflow-run object with its attempts and jobs,
   checking workflow identity consistency (name, path, event, actor,
   run ID uniqueness, head SHA shape, run conclusion agreement).

   done-runs accumulates fully parsed runs; runs-pages is the
   page count of the enclosing runs pagination. On a nested jobs (or
   job-attempts) pagination failure the run is kept — with its job
   list marked incomplete — and an :incomplete-observation naming
   :jobs and this run is thrown, carrying the runs parsed so far."
  [ctx seen-ids done-runs runs-pages run]
  (let [resource "workflow runs"]
    (when-not (map? run)
      (operational! "Malformed provider payload for workflow runs: expected an object"
                    {:resource resource}))
    (let [run-id (field! run "id" positive-int? resource)]
      (when (contains? @seen-ids run-id)
        (operational! "Inconsistent identity for workflow runs: duplicate run ID"
                      {:resource resource :run-id run-id}))
      (swap! seen-ids conj run-id)
      (let [run-resource (str "run " run-id)
            workflow-name (field! run "name" non-blank? resource)
            workflow-path (field! run "path" non-blank? resource)
            event (field! run "event" non-blank? resource)
            actor (actor-login! run resource)
            head-sha (field! run "head_sha" sha40? resource)
            stated-conclusion (gh/conclusion-from-provider
                               (field! run "conclusion" string? resource))
            attempts-url (field! run "attempts_url" non-blank? resource)
            jobs-url (field! run "jobs_url" non-blank? resource)
            run-url (field! run "url" non-blank? resource)
            attempts-pg (paginate! ctx :run-attempts nil
                                   (str "run " run-id " attempts") attempts-url)]
        (when-not (:pagination/complete attempts-pg)
          (nest-failing-step attempts-pg :runs {}
                             (str "run " run-id " attempts page")))
        (let [attempts (parse-attempt-objects! (:items attempts-pg) (str "run " run-id " attempts"))
              latest (apply max-key :github/attempt attempts)]
          (when (not= stated-conclusion (:github/conclusion latest))
            (operational! (str "Inconsistent identity for " run-resource
                               ": run conclusion differs from the latest attempt's conclusion")
                          {:resource run-resource}))
          (let [run-skeleton {:github/run-id run-id
                              :github/workflow-name workflow-name
                              :github/workflow-path workflow-path
                              :github/event event
                              :github/actor actor
                              :github/head-sha head-sha
                              :github/conclusion stated-conclusion
                              :github/attempts attempts}
                fail-run!
                (fn [jobs-pages failing-step]
                  (let [failing-run (assoc run-skeleton
                                           :github/jobs {:pagination/complete false
                                                         :pagination/pages jobs-pages
                                                         :jobs []}
                                           :evidence/url run-url)
                        partial (conj @done-runs failing-run)]
                    (incomplete! failing-step
                                 {:partial-runs partial :runs-pages runs-pages})))
                jobs-pg (paginate! ctx :jobs "jobs"
                                   (str "run " run-id " jobs") jobs-url
                                   {:github/run-id run-id})]
            (when-not (:pagination/complete jobs-pg)
              (fail-run! (:pagination/pages jobs-pg) (:failing-step jobs-pg)))
            (let [jobs (try
                         (mapv #(parse-job! ctx run-skeleton attempts %) (:items jobs-pg))
                         (catch clojure.lang.ExceptionInfo e
                           (if (= :job-attempts-failed (:axiom/error (ex-data e)))
                             (let [step (:failing-step (ex-data e))]
                               (fail-run!
                                (:page step)
                                {:collection :jobs
                                 :page (:page step)
                                 :reason (str "job " (:job-id (ex-data e))
                                              " attempts page: " (:reason step))
                                 :github/run-id run-id}))
                             (throw e))))]
              (assoc run-skeleton
                     :github/jobs {:pagination/complete true
                                   :pagination/pages (:pagination/pages jobs-pg)
                                   :jobs jobs}
                     :evidence/url run-url))))))))

(defn- parse-review!
  [review resource]
  (when-not (map? review)
    (operational! (str "Malformed provider payload for " resource ": expected an object")
                  {:resource resource}))
  (let [review-id (field! review "id" positive-int? resource)
        reviewer (let [user (field! review "user" map? resource)]
                   (field! user "login" login-like? resource))
        state (or (gh/review-state-from-provider (field! review "state" string? resource))
                    (operational! (str "Malformed provider payload for " resource
                                       ": invalid field \"state\"")
                                  {:resource resource :field "state"}))
        commit-sha (field! review "commit_id" sha40? resource)
        submitted-at (field! review "submitted_at"
                              (fn [v] (some? (instant->epoch! v "submitted_at" resource)))
                              resource)]
    {:github/review-id review-id
     :github/reviewer reviewer
     :github/state state
     :github/commit-sha commit-sha
     :github/submitted-at (instant->epoch! submitted-at "submitted_at" resource)
     :evidence/url (field! review "url" non-blank? resource)}))

(defn- parse-artifact!
  [artifact resource]
  (when-not (map? artifact)
    (operational! (str "Malformed provider payload for " resource ": expected an object")
                  {:resource resource}))
  (let [name (field! artifact "name" non-blank? resource)
        digest (get artifact "digest")]
    (when (and (some? digest) (not (digest-like? digest)))
      (operational! (str "Malformed provider payload for " resource ": invalid field \"digest\"")
                    {:resource resource :field "digest"}))
    {:github/artifact-name name
     :github/digest digest
     :github/expires-at (instant->epoch! (get artifact "expires_at") "expires_at" resource)
     :evidence/url (field! artifact "url" non-blank? resource)}))

;; ------------------------------------------------------------------
;; Observation assembly

(defn- provenance-map
  [ctx config]
  {:api/urls (:api/urls @(:provenance ctx))
   :api/etags (:api/etags @(:provenance ctx))
   :rate-limit (or @(:rate-limit ctx)
                   {:rate-limit/limit nil :rate-limit/remaining nil :rate-limit/reset nil})
   :http/client {:http/timeout-ms (:request-timeout-ms config)
                 :http/user-agent (:user-agent config)
                 :http/api-version (:api-version config)}})

(defn- collection-map
  "Port collection shape from a paginate! result. Incomplete
   collections never carry partial items."
  [pg items-key]
  (if (:pagination/complete pg)
    {:pagination/complete true
     :pagination/pages (:pagination/pages pg)
     items-key (:items pg)}
    {:pagination/complete false
     :pagination/pages (:pagination/pages pg)
     items-key []}))

(defn- empty-collection
  "An unfetched collection in an incomplete observation: marked
   incomplete with zero pages, never silently complete."
  [items-key]
  {:pagination/complete false :pagination/pages 0 items-key []})

(defn observe!
  "Read-only observation of a GitHub pull request.

   Options:
     :owner :repo   repository owner/name (required)
     :pr           pull request number (required)
     :token-file   path to a file holding the token (optional)
     :env          environment map (default: System/getenv);
                    AXIOM_GITHUB_TOKEN is read from here
     :fetch-fn     injectable fetch fn (default: the real JDK
                    HttpClient GET fetch); tests inject fixture fns
     :sleep-fn     wait between retries (default: Thread/sleep);
                    tests inject a recording no-op
     :clock-fn     zero-arg ms clock (default:
                    System/currentTimeMillis); tests inject a fixed clock
     :etag-cache   atom holding the bounded ETag cache (default: a
                    fresh cache); callers may share one across
                    observations
     :config       overrides for default-config

   A raw token is never accepted as an argument (pass :token-file or
   the environment instead) and never appears in errors or
   provenance.

   Returns a validated observation map (via `axiom.github`), with
   :observation/status :complete, or :observation/incomplete naming
   the failing collection and page when a paginated page fails after
   bounded retries.

   Throws :invalid for malformed caller input (bad owner/repo/pr,
   unreadable token file, a raw :token argument) and :operational
   for terminal API failures (401/403/404), retry exhaustion on
   single resources, malformed provider payloads, stale ETag cache
   hits, and workflow identity inconsistencies."
  [{:keys [owner repo pr token-file env fetch-fn sleep-fn clock-fn etag-cache config]
    :as opts}]
  (when (contains? opts :token)
    (model/invalid! "Raw tokens are never accepted as arguments; use :token-file or AXIOM_GITHUB_TOKEN" {}))
  (when-not (login-like? owner)
    (model/invalid! "Invalid repository owner" {:owner owner}))
  (when-not (repo-like? repo)
    (model/invalid! "Invalid repository name" {:repo repo}))
  (when-not (positive-int? pr)
    (model/invalid! "Invalid pull request number" {:pr pr}))
  (let [config (merge default-config config)
        credential (resolve-credential {:token-file token-file
                                        :env (or env (into {} (System/getenv)))})
        ctx {:fetch-fn (or fetch-fn (http-fetch-fn config))
             :sleep-fn (or sleep-fn (fn [ms] (Thread/sleep ^long ms)))
             :clock-ms (or clock-fn (fn [] (System/currentTimeMillis)))
             :config config
             :credential credential
             :owner owner :repo repo
             :base nil :head nil
             :etag-cache (or etag-cache (new-etag-cache))
             :provenance (atom {:api/urls [] :api/etags {}})
             :rate-limit (atom nil)}
        authenticated? (some? (:token credential))
        producer {:producer/id gh/producer-id
                  :producer/authenticated? authenticated?
                  :github/login nil}
        trust (if authenticated?
                gh/authenticated-trust
                gh/anonymous-trust)
        per-page (:per-page config)
        pr-url (str (:api-base config) "/repos/" owner "/" repo "/pulls/" pr)
        scope-of (fn [base head]
                   {:github/owner owner :github/repo repo :github/pr pr
                    :revisions {:base base :head head}})
        incomplete-observation
        (fn [producer* subject value failing-step]
          (gh/build-observation
           {:observation/status :observation/incomplete
            :producer producer*
            :trust trust
            :subject subject
            :value value
            :provenance (provenance-map ctx config)
            :scope (scope-of (:git/base subject) (:git/head subject))
            :observation/failing-step failing-step}))]
    ;; Token identity is resolved once per observation.
    (let [login (when authenticated? (:github/login (resolve-token-identity! ctx)))
          producer (assoc producer :github/login login)]
      (try
        ;; The PR scopes everything: base/head SHAs drive cache
        ;; invalidation, subject identity and the collection scope.
        (let [pr-body (fetch-single! ctx "pull request" :pr pr-url)
              {pr-record :pr/record base :git/base head :git/head head-repo :github/head-repo}
              (parse-pr! ctx pr-body pr-url pr)
              ctx (assoc ctx :base base :head head)
              subject {:github/owner owner :github/repo repo :github/pr pr
                       :git/base base :git/head head
                       :github/head-repo head-repo}
              progress (atom {})]
          (try
            (let [files-url (with-query (str pr-url "/files") {"per_page" per-page})
                  changes-pg (paginate! ctx :changes nil "changed files" files-url)]
              (when-not (:pagination/complete changes-pg)
                (incomplete! (:failing-step changes-pg)))
              (swap! progress assoc :changes
                     (collection-map changes-pg :changes)
                     :parsed-changes (mapv #(parse-change! % "changed files")
                                           (:items changes-pg)))
              (let [runs-url (with-query (str (:api-base config) "/repos/" owner "/" repo
                                              "/actions/runs")
                                         {"per_page" per-page})
                    runs-pg (paginate! ctx :runs "workflow_runs" "workflow runs" runs-url)]
                (when-not (:pagination/complete runs-pg)
                  (incomplete! (:failing-step runs-pg)))
                (let [seen-ids (atom #{})
                      done-runs (atom [])
                      runs (mapv (fn [r]
                                   (let [parsed (parse-run! ctx seen-ids done-runs
                                                            (:pagination/pages runs-pg) r)]
                                     (swap! done-runs conj parsed)
                                     parsed))
                                 (:items runs-pg))]
                  (swap! progress assoc :runs {:pagination/complete true
                                               :pagination/pages (:pagination/pages runs-pg)
                                               :runs runs}))
                (let [reviews-url (with-query (str pr-url "/reviews") {"per_page" per-page})
                      reviews-pg (paginate! ctx :reviews nil "reviews" reviews-url)]
                  (when-not (:pagination/complete reviews-pg)
                    (incomplete! (:failing-step reviews-pg)))
                  (swap! progress assoc :reviews (collection-map reviews-pg :reviews)
                         :parsed-reviews (mapv #(parse-review! % "reviews")
                                               (:items reviews-pg))))
                (let [artifacts-url (with-query (str (:api-base config) "/repos/" owner "/" repo
                                                     "/actions/artifacts")
                                                {"per_page" per-page})
                      artifacts-pg (paginate! ctx :artifacts "artifacts" "artifacts" artifacts-url)]
                  (when-not (:pagination/complete artifacts-pg)
                    (incomplete! (:failing-step artifacts-pg)))
                  (swap! progress assoc :artifacts (collection-map artifacts-pg :artifacts)
                         :parsed-artifacts (mapv #(parse-artifact! % "artifacts")
                                                 (:items artifacts-pg))))))
              (let [{:keys [changes parsed-changes runs reviews parsed-reviews
                            artifacts parsed-artifacts]} @progress]
                (gh/build-observation
                 {:observation/status :complete
                  :producer producer
                  :trust trust
                  :subject subject
                  :value {:pr/record pr-record
                          :changes (assoc changes :changes parsed-changes)
                          :runs runs
                          :reviews (assoc reviews :reviews parsed-reviews)
                          :artifacts (assoc artifacts :artifacts parsed-artifacts)}
                  :provenance (provenance-map ctx config)
                  :scope (scope-of base head)}))
            (catch clojure.lang.ExceptionInfo e
              (if (= :incomplete-observation (:axiom/error (ex-data e)))
                (let [{:keys [changes parsed-changes runs reviews parsed-reviews
                              artifacts parsed-artifacts]} @progress
                      exd (ex-data e)
                      failing-step (:failing-step exd)
                      ;; A nested :jobs failure keeps the runs parsed
                      ;; so far (including the failing run, whose job
                      ;; list is marked incomplete); a :runs failure
                      ;; carries no runs at all.
                      runs-value (if-let [partial (:partial-runs exd)]
                                   {:pagination/complete true
                                    :pagination/pages (:runs-pages exd)
                                    :runs partial}
                                   (or runs (empty-collection :runs)))
                      value {:pr/record pr-record
                             :changes (if changes
                                        (assoc changes :changes (or parsed-changes []))
                                        (empty-collection :changes))
                             :runs runs-value
                             :reviews (if reviews
                                        (assoc reviews :reviews (or parsed-reviews []))
                                        (empty-collection :reviews))
                             :artifacts (if artifacts
                                          (assoc artifacts :artifacts (or parsed-artifacts []))
                                          (empty-collection :artifacts))}]
                  (incomplete-observation producer subject value failing-step))
                (throw e)))))))))
