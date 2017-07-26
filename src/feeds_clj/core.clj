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

(defn read-file [fname]
  (-> fname abs-path slurp edn/read-string))

(defn write-file [fname contents]
  (with-open [w (-> fname abs-path clojure.java.io/writer)]
    (binding [*out* w]
      (clojure.pprint/write contents)
      (.write w "\n\n"))))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def feeds-by-user     (-> "feeds" read-file atom))
(def last-seen-by-user (-> "last_seen" read-file atom))
(def articles-by-feed
  (->> (for [feed (->> (u/glob data-folder #"/articles-.+\.edn") (map read-file))]
         [(:uri feed) feed])
       (into {})
       atom))


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
  (let [feed
        (try (fp/parse-feed feed-uri)
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
             (sort-by #(-> % :date .getTime -))
             (take 1000))]
    (swap! articles-by-feed assoc feed-uri
      (sorted-map
        :articles articles
        :uri      feed-uri
        :title    (:title feed)))
    articles))


(defn read-feeds []
  (->> (cp/upfor 20 [feed (->> @feeds-by-user (mapcat val) (into #{}))]
         [feed (-> feed read-feed count)])
       (into {})))


; Ref. https://stackoverflow.com/a/21404281/3731823
(defn periodically
  [f interval]
  (doto (Thread.
          #(try
             (while (not (.isInterrupted (Thread/currentThread)))
               (f)
               (Thread/sleep interval))
             (catch InterruptedException _)))
    (.start)))


(if (u/getenv "FEED_REFRESH")
   (periodically #(u/my-println (read-feeds)) (* 5 60 1000)))


(let [default-date (java.util.Date. 100 0 1)]
  (defn get-feeds-by-user
    ([user]
     (->> (@feeds-by-user user)
          (map @articles-by-feed)
          (map #(assoc % :latest-date (-> % :articles first :date)))
          (sort-by #(-> % :latest-date .getTime -))
          (map (fn [feed] (assoc feed :articles
                            (->> feed :articles
                                 (take-while #(> (-> % :date .getTime)
                                                 (-> @last-seen-by-user
                                                     (get user)
                                                     (get (:uri feed) default-date)
                                                     .getTime)))
                                 count))))))))


(comment
  (for [feed (->> @feeds-by-user (mapcat val) (into #{}))]
     (do (println feed)
         (-> feed read-feed count)))

  (get-feeds-by-user "nikonyrh")
  
  (read-feeds)
  
  (-> "https://feeds.yle.fi/uutiset/v1/recent.rss?publisherIds=YLE_UUTISET&concepts=18-34953"
      read-feed
      first
      pprint)
  
  (-> "https://feeds.yle.fi/uutiset/v1/recent.rss?publisherIds=YLE_UUTISET&concepts=18-34953"
      fp/parse-feed
      (dissoc :entries)
      pprint)
  
  (-> "https://utcc.utoronto.ca/~cks/space/blog/?atom"
      fp/parse-feed
      :entries
      first
      pprint))


(defn -ring-handler [request]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body
     (condp
        #(some->> %2 :uri (re-find %) rest) request
        
        #"^/feeds/([^/]+)$"
        :>> (fn [[user]]
              (let [_     (read-feeds)
                    feeds (get-feeds-by-user user)
                    html  (str
                            "<html><ul style='list-style-type: none'>\n"
                            (->> (for [feed feeds]
                                   ["  <li>[" (:articles feed) "] <a href='" (:uri feed) "'>"
                                    (:title feed)
                                    "</a>"
                                    " (" (->> feed :latest-date (.format (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss"))) ")"
                                    "</li>\n"])
                                 flatten
                                 clojure.string/join)
                            "</ul></html>")
                    _     (swap! last-seen-by-user assoc user (->> (for [feed feeds] [(:uri         feed)
                                                                                      (:latest-date feed)])
                                                                   (into (sorted-map))))]
               html))
        
        (str "<html><pre>"
             (-> request clojure.pprint/pprint with-out-str)
             "</pre></html>"))})


(defn -main []
  (jetty/run-jetty -ring-handler {:port 3000}))
