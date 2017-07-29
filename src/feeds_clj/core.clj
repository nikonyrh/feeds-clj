(ns feeds-clj.core
  (:require [com.climate.claypoole           :as cp]
            [nikonyrh-utilities-clj.core     :as u]
            [clojure.data.json               :as json]
            [clojure.java.io                 :as io]
            [clojure.edn                     :as edn]
            [feedparser-clj.core             :as fp]
            [ring.adapter.jetty              :as jetty])
  (:gen-class))

(def data-folder (u/getenv "DATA_HOME" (str (System/getProperty "user.home") "/projects/clojure/feeds-clj/data")))
(defn abs-path [fname]
  (if (= (first fname) \/)
    fname
    (str data-folder "/" fname ".edn")))

(defn read-file [fname & default]
  (try (-> fname abs-path slurp edn/read-string)
       (catch java.io.FileNotFoundException e (first default))))

(defn write-file [fname contents]
  (with-open [w (-> fname abs-path clojure.java.io/writer)]
    (binding [*out* w]
      (clojure.pprint/write contents)
      (.write w "\n\n"))))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def feeds-by-user     (-> "feeds" read-file atom))
(def last-seen-by-user (-> "last_seen" (read-file {}) atom))
(def articles-by-feed
  (->> (for [feed (->> (u/glob data-folder #"/articles-.+\.edn$") (map read-file))]
         [(:uri feed) feed])
       (into {}) atom))

(def feeds-by-hash (into {} (for [feed (->> @feeds-by-user (mapcat val) (into #{}))]
                              [(u/sha1-hash feed) feed])))

(do
  (add-watch last-seen-by-user :last-seen-by-user-to-file
    (fn [key ref old-value new-value]
      (if (not= old-value new-value)
        (write-file "last_seen" new-value))))
  
  (add-watch articles-by-feed :articles-by-feed-to-file
    (fn [key ref old-value new-value]
      (doseq [[key value] new-value :when (not= value (old-value key))]
        (write-file (str "articles-" (u/sha1-hash key)) value))))
  
  "Added watches")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn read-feed [feed-uri]
  (let [source
        (slurp feed-uri)
        
        feed
        (try (-> source (.getBytes "UTF-8") (java.io.ByteArrayInputStream.) fp/parse-feed)
             (catch com.rometools.rome.io.ParsingFeedException e
               (u/my-println "Exception " e " when fetching " feed-uri)))
        
        articles
        (->> (-> @articles-by-feed
                 (get feed-uri)
                 (get :articles []))
             (concat
               (for [article (get feed :entries [])]
                 (sorted-map
                   :uri   (:uri            article)
                   :date  (:published-date article)
                   :title (:title          article)
                   :desc  (-> article :description :value))))
             (u/my-distinct :uri)
             (sort-by #(some-> % :date .getTime -))
             (take 1000))
        
        result
        (sorted-map
          :source        source
          :articles      articles
          :uri           feed-uri
          :title         (:title feed)
          :latest-date   (-> articles first :date)
          :latest-uri    (-> articles first :uri))]
    (swap! articles-by-feed assoc feed-uri result)
    result))


(defn read-feeds []
  (->> (cp/upfor 20 [feed (->> @feeds-by-user (mapcat val) (into #{}))]
         [feed (-> feed read-feed :articles count)])
       (into {})))


; Ref. https://stackoverflow.com/a/21404281/3731823
(defn periodically [f interval]
  (doto (Thread.
          #(try
             (while (not (.isInterrupted (Thread/currentThread)))
               (f)
               (Thread/sleep interval))
             (catch InterruptedException _)))
    (.start)))


(if (u/getenv "FEED_REFRESH")
   (periodically #(u/my-println (read-feeds)) (* 30 60 1000))
   (read-feeds))


(defn get-feeds-by-user [user]
  (->> (@feeds-by-user user)
       (map @articles-by-feed)
       (map (fn [feed] 
              (let [last-seen-uri (get-in @last-seen-by-user [user (:uri feed)])]
                (update feed :articles
                  (fn [articles] (->> articles (take-while #(not= (:uri %) last-seen-uri)) count))))))
       (sort-by (comp - :articles))))


(comment
  (for [feed (->> @feeds-by-user (mapcat val) (into #{}))] (do (println feed) (-> feed read-feed :articles count)))
  (-> "https://feeds.yle.fi/uutiset/v1/recent.rss?publisherIds=YLE_UUTISET&concepts=18-34953" read-feed (dissoc :articles :source)))


(defn -ring-handler [request]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body
     (condp
        #(some->> %2 :uri (re-find %) rest) request
        
        #"^/feeds/by-user/([^/]+)$"
        :>> (fn [[user]]
              (let [_     (read-feeds)
                    feeds (get-feeds-by-user user)
                    html  (str
                            "<html>\n"
                            (->> (for [feed feeds]
                                   (let [[bs be] (when (pos? (:articles feed)) ["<b>" "</b>"])]
                                     (str
                                       "[" (:articles feed) "] "
                                       bs "<a href='/feeds/" user "/" (u/sha1-hash (:uri feed)) "'>" (:title feed) "</a>" be
                                       " (" (or (some->> feed :latest-date (.format (java.text.SimpleDateFormat. "yyyy-MM-dd"))) "????-??-??") ")"
                                       "<br/>\n")))
                                 clojure.string/join)
                            "</html>")]
               html))
        
        #"^/feeds/([^/]+)/([^/]+)$"
        :>> (fn [[user feed-hash]]
              (let [feed-uri (feeds-by-hash feed-hash)
                    feed     (read-feed feed-uri)
                    _        (swap! last-seen-by-user #(assoc-in % [user feed-uri] (:latest-uri feed)))]
                (:source (@articles-by-feed feed-uri))))
        
        (str "<html><pre>" (-> request clojure.pprint/pprint with-out-str) "</pre></html>"))})


; lein ring server
(defn -main []
  (jetty/run-jetty -ring-handler {:port 3000}))
