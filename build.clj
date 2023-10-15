(ns build
  (:refer-clojure :exclude [test])
  (:import [java.time LocalDate])
  (:require
    [clojure.string :as str]
    [clojure.tools.build.api :as b]
    [deps-deploy.deps-deploy :as dd]))

(def lib (quote space.matterandvoid/eql))
(def version (str/replace (str (LocalDate/now)) "-" "."))
(def class-dir "target/classes")

(defn test "Run all the tests." [opts]
  (let [basis (b/create-basis {:aliases [:test]})
        cmds (b/java-command
               {:basis     basis
                :main      'clojure.main
                :main-args ["-m" "cognitect.test-runner"]})
        {:keys [exit]} (b/process cmds)]
    (when-not (zero? exit) (throw (ex-info "Tests failed" {}))))
  opts)

(defn- pom-template [version]
  [[:description "EDN Query Language - EQL"]
   [:url "https://github.com/matterandvoid-space/eql"]
   [:licenses
    [:license
     [:name "MIT"]
     [:url "https://opensource.org/licenses/MIT"]]]
   [:developers
    [:developer [:name "Wilker Lúcio"]]
    [:developer [:name "Daniel Vingo"]]]
   [:scm
    [:url "https://github.com/matterandvoid-space/eql"]
    [:connection "scm:git:https://github.com/matterandvoid-space/eql.git"]
    [:developerConnection "scm:git:ssh:git@github.com:matterandvoid-space/eql.git"]
    [:tag (str "v" version)]]])

(defn- jar-opts [opts]
  (assoc opts
    :lib lib :version version
    :jar-file (format "target/%s-%s.jar" lib version)
    :basis (b/create-basis {})
    :class-dir class-dir
    :target "target"
    :src-dirs ["src"]
    :pom-data (pom-template version)))

(defn ci "Run the CI pipeline of tests (and build the JAR)." [opts]
  (test opts)
  (b/delete {:path "target"})
  (let [opts (jar-opts opts)]
    (println "\nWriting pom.xml...")
    (b/write-pom opts)
    (println "\nCopying source...")
    (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
    (println "\nBuilding JAR..." (:jar-file opts))
    (b/jar opts))
  opts)

(defn install "Install the JAR locally." [opts]
  (let [opts (jar-opts opts)]
    (b/install opts))
  opts)

(defn deploy "Deploy the JAR to Clojars." [opts]
  (let [{:keys [jar-file] :as opts} (jar-opts opts)]
    (dd/deploy {:installer :remote :artifact (b/resolve-path jar-file)
                :pom-file  (b/pom-path (select-keys opts [:lib :class-dir]))}))
  opts)
