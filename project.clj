(defproject coms-middleware "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojars.sn0wf1eld/clojure-data-grinder-core "0.2.3"]
                 [org.clojure/tools.logging "1.2.3" :scope "provided"]
                 [pt.iceman/comms-common "1.0-SNAPSHOT"]
                 [com.layerware/hugsql "0.4.5"]
                 [clj-time "0.15.2" :scope "provided"]
                 [compojure "1.6.2" :exclusions [clj-time] :scope "provided"]
                 [ring/ring-json "0.5.1" :exclusions [clj-time] :scope "provided"]
                 [ring-cors "0.1.13" :scope "provided"]
                 [http-kit "2.5.3" :scope "provided"]
                 [mockery "0.1.4" :scope "test"]]
  :repl-options {:init-ns coms-middleware.core})
