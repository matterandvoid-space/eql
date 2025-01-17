(ns build
  (:refer-clojure :exclude [test])
  (:import [java.time LocalDate])
  (:require
    [clojure.string :as str]
    [clojure.tools.build.api :as b]
    [clojure.data.xml :as xml]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [deps-deploy.deps-deploy :as dd]))

(defn mk-dep [g a v]
  (let [pom-ns "http://maven.apache.org/POM/4.0.0"]
    (xml/sexp-as-element
      [(xml/qname pom-ns "dependency")
       [(xml/qname pom-ns "groupId") g]
       [(xml/qname pom-ns "artifactId") a]
       [(xml/qname pom-ns "version") v]
       [(xml/qname pom-ns "scope") "provided"]])))

(def provided-deps
  (let [deps-map (-> "deps.edn" slurp edn/read-string :aliases :provided :extra-deps)]
    (mapv (fn [[k v]] (mk-dep (namespace k) (name k) (:mvn/version v)))
      deps-map)))

(defn read-xml-file [file]
  (with-open [rdr (io/reader file)]
    (xml/parse rdr)))

(defn write-xml-file [file xml-data]
  (with-open [wrtr (io/writer file)]
    (xml/emit xml-data wrtr)))

(defn find-and-update-dependencies [content deps]
  (map (fn [elem]
         (if (= ((fnil name "") (:tag elem)) "dependencies")
           (assoc elem :content deps
             ;; if you don't want to overwrite use update:  (comp vec concat) deps
             )
           elem))
    content))

(defn insert-dependencies [pom-file deps]
  (let [pom-data (read-xml-file pom-file)
        updated-content (find-and-update-dependencies (:content pom-data) deps)
        updated-pom-data (assoc pom-data :content updated-content)]
    updated-pom-data))

;; not curently used, since the jar creation creates the necessary dir structure already before writing the updated pom.xml file
(defn ensure-dirs-exist [file-path]
  (let [dir (io/file file-path)]
    (.mkdirs (.getParentFile dir))))
(comment
  (insert-dependencies "pom-template.xml" provided-deps)

  (write-xml-file "new-pom.xml"
    (insert-dependencies "pom-template.xml" provided-deps))
  )

(def lib (quote space.matterandvoid/eql))
(def version (str (str/replace (str (LocalDate/now)) "-" ".")))
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
    (println "\nWriting pom.xml.")
    (b/write-pom opts)
    (println "\nCopying source.")
    (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
    (println "\nBuilding JAR." (:jar-file opts))
    (b/jar opts)
    (println "\nWriting pom.xml with provided deps.")
    (write-xml-file "target/classes/META-INF/maven/space.matterandvoid/eql/pom.xml"
      (insert-dependencies "target/classes/META-INF/maven/space.matterandvoid/eql/pom.xml" provided-deps)))
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
