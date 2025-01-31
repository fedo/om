(ns om.next.tests
  (:require [cljs.test :refer-macros [deftest is testing run-tests]]
            [goog.object :as gobj]
            [clojure.zip :as zip]
            [om.next :as om :refer-macros [defui]]
            [om.next.protocols :as p]
            [om.next.impl.parser :as parser]
            [om.dom :as dom]))

;; -----------------------------------------------------------------------------
;; Components

(defui ^:once Component
  static om/IQuery
  (query [this]
    '[:foo/bar :baz/woz]))

(def component (om/factory Component))

(defui ComponentList
  static om/IQueryParams
  (params [this]
    {:component (om/get-query Component)})
  static om/IQuery
  (query [this]
    '[{:components/list ?component} :app/title]))

(def component-list (om/factory ComponentList))

(deftest test-component?
  (is (om/component? (Component. {}))))

(deftest test-pr-str-component
  (is (= (pr-str Component) "om.next.tests/Component")))

(deftest test-construction
  (let [c (component-list {:foo/bar 1})]
    (is (= (om/react-type c) ComponentList))
    (is (= (om/t c) 0))))

;; -----------------------------------------------------------------------------
;; Queries

(deftest test-query
  (is (= (om/query Component)
         '[:foo/bar :baz/woz]))
  (is (= (om/query ComponentList)
         '[{:components/list ?component} :app/title])))

(deftest test-get-query
  (is (= (om/get-query Component)
         '[:foo/bar :baz/woz]))
  (is (= (om/get-query ComponentList)
         '[{:components/list [:foo/bar :baz/woz]} :app/title])))

(deftest test-focus-selector
  (is (= (om/focus-query [:foo/bar] [])
         [:foo/bar]))
  (is (= (om/focus-query
           [:foo/bar {:baz/woz [:goz/noz]}]
           [:baz/woz])
         [{:baz/woz [:goz/noz]}]))
  (is (= (om/focus-query
           [:foo/bar {:baz/woz [:goz/noz {:bop/wop [:nop/sop]} :cuz/wuz]}]
           [:baz/woz :bop/wop])
        [{:baz/woz [{:bop/wop [:nop/sop]}]}])))

(deftest test-focus->path
  (is (= (om/focus->path [{:baz/woz [{:bop/wop [:nop/sop]}]}])
         [:baz/woz :bop/wop]))
  (is (= (om/focus->path [:app/title {:counters/list [:db/id :counter/count]}])
         [])))

(deftest test-om-423
  (is (= (om/focus-query '[:foo ({:people [:name :age]} {:length 3}) :bar] [:people])
         '[({:people [:name :age]} {:length 3})])))

;; -----------------------------------------------------------------------------
;; Query Templating

(deftest test-node->key
  (is (= :foo (om/node->key {:foo []})))
  (is (= :foo (om/node->key '({:foo []} {:bar :baz})))))

(deftest test-query-template
  (is (= [:app/title {:todos/list [:db/id :todo/title]}]
         (-> (om/query-template
               [:app/title {:todos/list [:db/id :todo/title :todo/completed]}]
               [:todos/list])
           (om/replace [:db/id :todo/title]))))
  (is (= '[:app/title ({:todos/list [:db/id :todo/title]} {:start 0 :end 10})]
         (-> (om/query-template
               '[:app/title ({:todos/list [:db/id :todo/title :todo/completed]}
                             {:start 0 :end 10})]
               '[:todos/list])
           (om/replace [:db/id :todo/title])))))

(deftest test-query-template-root
  (is (= [:app/title]
         (-> (om/query-template
               [:app/title {:todos/list [:db/id :todo/title :todo/completed]}]
               [])
           (om/replace [:app/title])))))

;; -----------------------------------------------------------------------------
;; Indexer

(deftest test-indexer
  (let [idxr (om/indexer)
        idxs (p/index-root idxr ComponentList)]
    (is (= (set (keys (:prop->classes idxs)))
           #{:app/title :components/list :foo/bar :baz/woz}))
    ))

(deftest test-reconciler-has-indexer
  (let [r (om/reconciler
            {:state (atom nil)
             :ui->ref identity})]
    (is (instance? om/Indexer (get-in r [:config :indexer])))))

;; -----------------------------------------------------------------------------
;; Parser

(deftest test-expr->ast
  (is (= (parser/expr->ast :foo)
         {:type :prop :key :foo :dkey :foo}))
  (is (= (parser/expr->ast [:foo 0])
         {:type :prop :key [:foo 0] :dkey :foo :params {:om.next/refid 0}}))
  (is (= (parser/expr->ast {:foo [:bar]})
         {:type :prop :key :foo :dkey :foo :sel [:bar]}))
  (is (= (parser/expr->ast {[:foo 0] [:bar]})
          {:type :prop :key [:foo 0] :dkey :foo :params {:om.next/refid 0} :sel [:bar]}))
  (is (= (parser/expr->ast '(:foo {:bar 1}))
         {:type :prop :key :foo :dkey :foo :params {:bar 1}}))
  (is (= (parser/expr->ast '({:foo [:bar :baz]} {:woz 1}))
         {:type :prop :key :foo :dkey :foo :sel [:bar :baz] :params {:woz 1}}))
  (is (= (parser/expr->ast '({[:foo 0] [:bar :baz]} {:woz 1}))
         {:type :prop :key [:foo 0] :dkey :foo :sel [:bar :baz] :params {:om.next/refid 0 :woz 1}}))
  (is (= (parser/expr->ast '(do/it {:woz 1}))
         {:type :call :key 'do/it :dkey 'do/it :params {:woz 1}})))

(deftest test-ast->expr
  (is (= (parser/ast->expr {:type :prop :key :foo :dkey :foo})
         :foo))
  (is (= (parser/ast->expr {:type :prop :key [:foo 0] :dkey :foo :params {:om.next/refid 0}})
         [:foo 0]))
  (is (= (parser/ast->expr {:type :prop :key :foo :dkey :foo :sel [:bar]})
         {:foo [:bar]}))
  (is (= (parser/ast->expr {:type :prop :key [:foo 0] :dkey :foo :params {:om.next/refid 0} :sel [:bar]})
         {[:foo 0] [:bar]}))
  (is (= (parser/ast->expr {:type :prop :key :foo :dkey :foo :params {:bar 1}})
         '(:foo {:bar 1})))
  (is (= (parser/ast->expr {:type :prop :key :foo :dkey :foo :sel [:bar :baz] :params {:woz 1}})
         '({:foo [:bar :baz]} {:woz 1})))
  (is (= (parser/ast->expr {:type :prop :key [:foo 0] :dkey :foo :sel [:bar :baz] :params {:om.next/refid 0 :woz 1}})
         '({[:foo 0] [:bar :baz]} {:woz 1})))
  (is (= (parser/ast->expr {:type :call :key 'do/it :dkey 'do/it :params {:woz 1}})
         '(do/it {:woz 1}))))

(defmulti read (fn [env k params] k))

(defmethod read :default
  [{:keys [state data]} k params]
  (if (and (not (nil? data)) (contains? data k))
    {:value (get data k)}
    {:remote true}))

(defmethod read :foo/bar
  [{:keys [state]} k params]
  (if-let [v (get @state k)]
    {:value v}
    {:remote true}))

(defmethod read :woz/noz
  [{:keys [state]} k params]
  (if-let [v (get @state k)]
    {:value v :remote true} ;; local read AND remote read
    {:remote true})) ;; no cached locally, must read remote

(defmethod read :user/pic
  [env k {:keys [size]}]
  (let [size-str (case size :small "50x50" :large "100x100")]
    {:value (str "user" size-str ".png") :remote true}))

(defmethod read :user/by-id
  [{:keys [selector] :as env} k {:keys [id] :as params}]
  {:value (cond-> {:name/first "Bob" :name/last "Smith"}
            selector (select-keys selector))
   :remote true})

(defmulti mutate (fn [env k params] k))

(defmethod mutate 'do/it!
  [{:keys [state]} k {:keys [id]}]
  {:value [id]
   :action #()
   :remote true})

(def p (om/parser {:read read :mutate mutate}))

(deftest test-basic-parsing
  (let [st (atom {:foo/bar 1})]
    (is (= (p {} [:baz/woz]) {}))
    (is (= (p {:state st} [:foo/bar]) {:foo/bar 1}))
    (is (= (p {:state st} [:foo/bar :baz/woz]) {:foo/bar 1}))
    (is (= (p {} [:baz/woz] {:remote true}) [:baz/woz]))
    (is (= (p {:state st} [:foo/bar] {:remote true}) []))
    (is (= (p {:state st} [:foo/bar :baz/woz] {:remote true}) [:baz/woz]))))

(deftest test-value-and-remote
  (let [st (atom {:woz/noz 1})]
    (is (= (p {:state st} [:woz/noz]) {:woz/noz 1}))
    (is (= (p {:state st} [:woz/noz] {:remote true}) [:woz/noz]))))

(deftest test-call
  (let [st (atom {:foo/bar 1})]
    (is (= (p {:state st} '[(do/it! {:id 0})]) '{do/it! [0]}))
    (is (= (p {} '[(do/it! {:id 0})] {:remote true})
           '[(do/it! {:id 0})]))))

(deftest test-read-call
  (let [st (atom {:foo/bar 1})]
    (is (= (p {:state st} '[(:user/pic {:size :small})])
           {:user/pic "user50x50.png"}))
    (is (= (p {:state st} '[(:user/pic {:size :small})] {:remote true})
           '[(:user/pic {:size :small})]))))

(defmethod mutate 'mutate!
  [{:keys [state]} k params]
  {:value  []
   :action #(swap! state update-in [:count] inc)} )

(deftest test-remote-does-not-mutate
  (let [st (atom {:count 0})
        _  (p {:state st} '[(mutate!)])
        _  (p (:state st) '[(mutate!)] {:remote true})]
    (is (= @st {:count 1}))))

(defmethod read :now/wow
  [{:keys [state selector]} k params]
  {:value {:selector selector :params params}})

(deftest test-parameterized-join
  (let [st (atom {:foo/bar 1})]
    (is (= (p {:state st} '[({:now/wow [:a :b]} {:slice [10 20]})])
           '{:now/wow {:selector [:a :b] :params {:slice [10 20]}}}))))

(deftest test-refs
  (let [st (atom {:foo/bar 1})]
    (is (= (p {:state st} [[:user/by-id 0]])
           {[:user/by-id 0] {:name/first "Bob" :name/last "Smith"}}))
    (is (= (p {:state st} [[:user/by-id 0]] {:remote true})
           [[:user/by-id 0]]))
    (is (= (p {:state st} [{[:user/by-id 0] [:name/last]}])
           {[:user/by-id 0] {:name/last "Smith"}}))
    (is (= (p {:state st} [{[:user/by-id 0] [:name/last]}] {:remote true})
           [{[:user/by-id 0] [:name/last]}]))))

(deftest test-forced-remote
  (is (= (p {} '['(foo/bar)]) {}))
  (is (= (p {} '['(foo/bar)] {:remote true}) '[(foo/bar)])))

(defmethod mutate 'this/throws
  [_ _ _]
  {:action #(throw (js/Error.))})

(deftest test-throw
  (is (instance? js/Error (get (p {} '[(this/throws)]) 'this/throws))))

;; -----------------------------------------------------------------------------
;; Edge cases

(defmethod read :missing/thing
  [env k params]
  {})

(deftest test-missing-value
  (is (= (p {} [:missing/thing]) {})))

(defmethod mutate 'remote/action
  [env k params] {:remote true})

(defmethod mutate 'action/no-value
  [{:keys [state] :as env} k params]
  {:action (fn [] (reset! state :changed))})

(deftest test-action-no-value
  (let [state (atom nil)]
    (is (= (p {:state state} '[(action/no-value)]) {}))
    (is (= :changed @state)))
  (let [state (atom nil)]
    (is (= (p {:state state} '[(action/no-value)] {:remote true}) []))
    (is (= nil @state))))

;; -----------------------------------------------------------------------------
;; Recursive Parsing

(def todos-state
  (atom
    {:todos
     {0 {:id 0
         :title "Walk dog"
         :completed false
         :category 0}
      1 {:id 0
         :title "Get milk"
         :completed true
         :category 0}
      2 {:id 0
         :title "Finish Om Next"
         :completed false
         :category 1}}
     :categories {0 :home 1 :work}
     :todos/list [0 1 2]}))

(defmethod read :category
  [{:keys [state data]} k]
  {:value (get-in @state [:categories (get data k)])})

(defmethod read :todos/list
  [{:keys [state selector parse] :as env} _]
  (let [st @state
        pf #(parse (assoc env :data %) selector)]
    {:value (into [] (comp (map (:todos st)) (map pf))
              (:todos/list st))}))

(deftest test-recursive-parse
  (is (= (p {:state todos-state} '[{:todos/list [:title :category]}])
         '{:todos/list [{:title "Walk dog", :category :home}
                        {:title "Get milk", :category :home}
                        {:title "Finish Om Next", :category :work}]})))

;; -----------------------------------------------------------------------------
;; Normalization

(def data
  {:list/one [{:name "John" :points 0 :friend {:name "Bob"}}
              {:name "Mary" :points 0 :foo :bar}
              {:name "Bob" :points 0 :friend {:name "John"}}]
   :list/two [{:name "Gwen" :points 0 :friends [{:name "Jeff"}]}
              {:name "Mary" :points 0 :baz :woz}
              {:name "Jeff" :points 0 :friends [{:name "Gwen"}]}]})

(defui Person
  static om/Ident
  (ident [this {:keys [name]}]
    [:person/by-name name])
  static om/IQuery
  (query [this]
    [:name :points
     {:friend (om/tag [:name] Person)}
     {:friends (om/tag [:name] Person)}
     :foo :baz])
  Object
  (render [this]))

(defui ListView
  Object
  (render [this]))

(defui RootView
  static om/IQuery
  (query [this]
    (let [subquery (om/get-query Person)]
      [{:list/one subquery} {:list/two subquery}]))
  Object
  (render [this]))

(deftest test-normalize
  (let [norm (om/normalize RootView data)
        refs (meta norm)
        p0   (get-in refs [:person/by-name "Mary"])]
    (is (= 3 (count (get norm :list/one))))
    (is (= {:name "John" :points 0 :friend [:person/by-name "Bob"]}
           (get-in refs [:person/by-name "John"])))
    (is (= 3 (count (get norm :list/two))))
    (is (contains? p0 :foo))
    (is (contains? p0 :baz))))

(deftest test-incremental-normalize
  (let [p0   (om/normalize Person
               {:name "Susan" :points 5 :friend {:name "Mary"}})
        refs (meta p0)]
    (is (= {:name "Susan" :points 5 :friend [:person/by-name "Mary"]}
           p0))
    (is (= refs {:person/by-name {"Mary" {:name "Mary"}}}))))

(comment
  (require '[cljs.pprint :as pp])
  (run-tests)
  )

;; -----------------------------------------------------------------------------
;; Message Forwarding

(defui Post
  static om/IQuery
  (query [this]
    [:id :type :title :author :content]))

(defui Photo
  static om/IQuery
  (query [this]
    [:id :type :title :image :caption]))

(defui Graphic
  static om/IQuery
  (query [this]
    [:id :type :title :image]))

(defui DashboardItem
  static om/Ident
  (ident [this {:keys [id type]}]
    [type id])
  static om/IQuery
  (query [this]
    (zipmap
      [:dashboard/post :dashboard/photo :dashboard/graphic]
      (map #(conj % :favorites)
        [(om/get-query Post)
         (om/get-query Photo)
         (om/get-query Graphic)]))))

(defui Dashboard
  static om/IQuery
  (query [this]
    [{:dashboard/items (om/get-query DashboardItem)}]))

(defmulti read1 om/dispatch)

(defmethod read1 :default
  [_ _ _])

(defmethod read1 :favorites
  [_ _ _]
  {:remote true})

(defmethod read1 :dashboard/items
  [{:keys [parse ast] :as env} _ _])

(comment
  (om/get-query Dashboard)

  (def parser (om/parser {:read read1}))

  (parser {} (om/get-query Dashboard) {:remote true})
  )