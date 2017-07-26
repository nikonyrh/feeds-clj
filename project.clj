(defproject feeds-clj "0.0.1-SNAPSHOT"
  :description "FIXME: write description"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/data.json "0.2.6"]
                 [ring/ring-core "1.5.1"]
                 [com.climate/claypoole "1.1.4"]
                 [ring/ring-core "1.2.1"]
                 [ring/ring-jetty-adapter "1.2.1"]
                 [org.clojars.nikonyrh.utilities-clj "0.1.4"]
                 [org.clojars.freemarmoset/feedparser-clj "0.6.1"]]
  :javac-options ["-target" "1.6" "-source" "1.6" "-Xlint:-options"]
  :java-source-paths ["src/java"]
  :aot [feeds-clj.core]
  :main feeds-clj.core
  :plugins [[lein-ring "0.11.0"]]
  :ring {:handler feeds-clj.core/-ring-handler})
