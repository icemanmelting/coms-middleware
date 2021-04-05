(defproject coms-middleware "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [clojure-data-grinder-core "0.1.0"]
                 [pt.iceman/comms-common "1.0-SNAPSHOT"]
                 [org.postgresql/postgresql "42.1.1"]
                 [com.layerware/hugsql "0.4.5"]
                 [mockery "0.1.4" :scope "test"]]
  :repl-options {:init-ns coms-middleware.core})
