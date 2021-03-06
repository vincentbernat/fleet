(ns org.spootnik.fleet.transport.redis
  (:require [clojure.core.async           :refer [>!! chan pub sub unsub-all]]
            [clojure.string               :refer [split join]]
            [clojure.tools.logging        :refer [warn]]
            [org.spootnik.fleet.codec     :as codec]
            [org.spootnik.fleet.transport :as transport]
            [org.spootnik.fleet.security  :as security]
            [org.spootnik.fleet.service   :as service]))

(defn subscriber
  [ch codec signer]
  (proxy [redis.clients.jedis.JedisPubSub] []
    (onSubscribe [^String chan subscription-count])
    (onPSubscribe [^String chan subscription-count])
    (onPUnsubscribe [^String chan subscription-count])
    (onUnsubscribe [^String chan subscription-count])
    (onMessage [^String chan ^String msg])
    (onPMessage [^String pattern ^String chan ^String msg]
      (let [sig     (last (split chan #":"))
            decoded (codec/decode codec msg)]
        (try
          (security/verify signer (:host decoded) msg sig)
          (>!! ch {:chan chan :msg decoded})
          (catch Exception e
            (warn "received message with bad signature from "
                  (:host decoded))))))))

(defn rpool
  [{:keys [host port timeout max-active max-idle max-wait]
    :or {max-active 5
         max-wait   200
         timeout    200
         host       "localhost"
         port       6379}}]
  (-> (doto (redis.clients.jedis.JedisPoolConfig.)
        (.setMaxActive max-active)
        (.setMaxIdle (or max-idle max-active))
        (.setTestOnBorrow true)
        (.setMaxWait max-wait))
      (redis.clients.jedis.JedisPool. host port timeout)))

(defn rpublish
  [pool chan msg]
  (let [client (.getResource pool)]
    (try
      (.publish client chan msg)
      (finally (.returnResource pool client)))))

(defn get-chan
  [{:keys [chan]}]
  (when chan
    (let [parts (split chan #":")]
      (join ":" (butlast parts)))))

(defn redis-transport
  [{:keys [inbuf] :or {inbuf 10}} codec signer]

  (let [ch   (chan inbuf)
        pool (rpool {})
        pub  (pub ch get-chan)
        ptrn "fleet:*"]

    (reify
      service/Service
      (start! [this]
        (future
          (let [redis (.getResource pool)]
            (try
              (.psubscribe redis (subscriber ch codec signer)
                           (into-array String [ptrn]))
              (finally (.returnResource pool redis))))))

      transport/Transport
      (publish [this chan msg]
        (let [client (.getResource pool)]
          (try
            (let [payload (codec/encode codec msg)
                  sig     (security/sign signer payload)]
              (.publish client (str chan ":" sig) payload))
            (finally (.returnResource pool client)))))
      (subscribe [this chans]
        (mapv (partial sub pub) chans (repeatedly #(chan inbuf))))
      (unsubscribe [this chans]
        (doseq [chan chans]
          (unsub-all pub chan))))))
