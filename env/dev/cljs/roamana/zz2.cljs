(ns roamana.zz2
  (:require [reagent.core :as reagent :refer [atom]]
            [re-frame.db :refer [app-db]]
            [re-frame.core :refer [subscribe dispatch register-handler register-sub]]
            [posh.core :refer [posh!] :as posh]
            [datascript.core :as d]
            [com.rpl.specter  :refer [ALL STAY MAP-VALS LAST
                                      stay-then-continue 
                                      if-path END cond-path
                                      must pred keypath
                                      collect-one comp-paths] :as sp]
            [roamana.logger :refer [all-ents]]
            [roamana.views :refer [main-view logmap todo-create] :as views]
            [reagent.session :as session]
            [keybind.core :as key]
            [cljs.spec  :as s]
            [roamana.zz :refer [cursify]]
            [goog.i18n.DateTimeFormat :as dtf]
            [roamana.core :as core])
  (:require-macros
   [com.rpl.specter.macros  :refer [select setval defnav 
                                    defpathedfn
                                    defnavconstructor
                                    fixed-pathed-nav
                                    variable-pathed-nav
                                    transform declarepath providepath]]
   [reagent.ratom :refer [reaction]]
   [devcards.core
    :as dc
    :refer [defcard defcard-doc defcard-rg deftest]]))

(re-frame.utils/set-loggers! {:warn #(js/console.log "")})

(enable-console-print!)

(def schema {:node/children {:db/valueType :db.type/ref
                             :db/cardinality :db.cardinality/many}})


(defonce conn (d/create-conn schema))
(posh! conn)

(def cc (cursify conn))



(d/transact! conn [{:db/id 0 :node/type :root :node/children #{1 2}}
                   {:db/id 1 :node/type :text :node/text "Main 1"  
                    :node/children #{3}} 
                   {:db/id 2 :node/type :text :node/text "Main 2"
                    :node/children #{3}} 
                   {:db/id 3 :node/type :text :node/text "Main 1 &2 : Child 1"
                    :node/children #{4}} 
                   {:db/id 4 :node/type :text :node/text "Child 1: Grandkid 1"
                    :node/children #{5}}
                   {:db/id 5 :node/type :text :node/text "Grandkid 1 : Great 1"} ])


(register-sub
 ::all
 (fn [db]
   (reaction @db)))


(register-sub
 ::all-ents
 (fn [db [_ conn]]
   (posh/q conn '[:find (pull ?e [*])
                    :where  [?e]])))


(defn ents [conn]
  (let [es (subscribe [::all-ents conn])
        all (subscribe [::all])]
  (fn []
    [:div 
     [:div (pr-str @all)]])))



;;root is hidden, or s at the top
(declare tree->lists)

(declare followpath)

(register-handler
 ::state-from-conn
 (fn [db [_ conn]]
   (let [root (posh/pull conn '[*] (:root-eid db 0))
         path (:path db :node/children)
         vdepth (:visible-depth db 4)
         errthin (posh/pull conn `[:db/id {~path ~vdepth}] (:db/id @root))]
     (merge db    
            {:root @root
             :depth 0
             :cursor (vec (for [i (range vdepth)] 0))
             :root-list (followpath [path ALL] :db/id vdepth @errthin)}))))


(dispatch [::state-from-conn conn])


;(def ptest @(posh/pull conn '[{:node/_children ...}] 4))


#_(declarepath repeat-path [walk-path end-path i])



(defpathedfn repeat-path [walk-path end-path i]
  (if (= 0 i)
    end-path
    [walk-path (repeat-path walk-path end-path (dec i))]))



(defn followpath [walkpath endpath depth tree]
  (vec  (for [i (range depth)] 
        (vec (set (select (repeat-path walkpath endpath i) tree))))))



#_(defcard-rg errthing
  [:div
   [:button {:on-click #(dispatch [::state-from-conn conn])} "statre"]
   [:button {:on-click #(dispatch [::nav-mode conn])} "SETUP"]
   [ents conn]])



(register-sub
 ::key
 (fn [db [_ k]]
   (reaction (get @db k))))


(register-sub
 ::root-list
 (fn [db _ [conn]]
   (reaction (:root-list @db))))


(register-handler
 ::assoc
 (fn [db [_ k v]]
   (assoc db k v)))



(defn cell-view [conn column-depth cell-index eid]
 (let [cursor (subscribe [::key :cursor])
       depth (subscribe [::key :depth])] 
   (fn [conn column-depth cell-index eid]
     [:div {}
      [:button 
       {:style 
            {:background-color 
             (if (and (= @depth column-depth) 
                      (=  (nth @cursor @depth) cell-index))
               "green"
               "blue")}
        :on-click #(dispatch [::move-cursor column-depth cell-index])} 
       eid]
      [:a {:on-click #(do 
                        (dispatch [::assoc :root-eid eid])
                        (dispatch [::state-from-conn conn]))} :r]])))


(defn column [conn column-index column-val]
  (let [cursor (subscribe [::key :cursor])
        depth (subscribe [::key :depth])]
    (fn [conn column-index column-val]
      [:div
       {:style {:flex-grow 1
                :border (if (= column-index @depth)
                          "2px solid red"
                          "1px solid black")}}
       (doall (for [[r cell] (map-indexed vector column-val)]
                ^{:key cell}[cell-view conn column-index r cell]))])))




(register-handler
 ::set-root
 (fn [db [_ conn eid]]
   (dispatch [::state-from-conn conn])
   (assoc db :root-eid eid)))


(defn gridview [conn]
  (let [depth (subscribe [::key :depth])
        list (subscribe [::root-list])]
    (fn [conn]
      [:div {:style {:display "flex"
                     :flex-direction "row"}}
       [:button {:on-click #(dispatch [::set-root conn 0])} 0]
       (for [[d c] (map-indexed vector @list)]
          ^{:key (str d column)} [column conn d c])])))


(declare vec-keysrf)

#_(defcard-rg v3
  [:div
   [:button {:on-click #(vec-keysrf conn)} "hey"]
   [gridview conn]])


(register-handler 
 ::inc-depth
 (fn [db]
   (do (js/console.log (pr-str (count (:root-list db))))
       (if (= (inc (:depth db)) (count (:root-list db)))
         (assoc db :depth 0)
         (update db :depth inc)))))


(register-handler 
 ::dec-depth
 (fn [db]
   (if (= (:depth db) 0) 
     (assoc db :depth (dec (count (:root-list db))))
     (update db :depth dec)
     )))




(register-handler
 ::move-cursor
 (fn [db [_ cell-depth newval]]
   (->> (setval [:cursor (keypath cell-depth)] newval db)
        (setval [:depth] cell-depth))))




(register-handler
 ::dec-cursor
 (fn [db]
   (let [d (:depth db) ]
     (transform [:cursor (keypath d)]
                (fn [v] 
                  (if (= 0 v)
                    (dec (-> db
                             :root-list
                             (nth d)
                             count))
                    (dec v)))
                db))))



(register-handler
 ::inc-cursor
 (fn [db]
   (let [d (:depth db) ]
     (transform [:cursor (keypath d)]
                (fn [v] 
                  (if (= (inc v) (-> db
                                     :root-list
                                     (nth d)
                                     count))
                    0
                    (inc v)))
                db))))

(defn nav-keys [{:keys [left right down up]}]
  (key/bind! right ::right #(dispatch [::inc-depth]))
  (key/bind! left ::left #(dispatch [::dec-depth]))
  (key/bind! up ::up #(dispatch [::dec-cursor]))
  (key/bind! down ::down  #(dispatch [::inc-cursor]))
)


(defn vec-keysrf [conn]
  (key/unbind-all!)
  (nav-keys {:left "h" :right "l" :down "j" :up "k" })
  (key/bind! "i" ::add-child  #(dispatch [::add-child conn]))
)



#_(defcard-rg v4*
  [gridview conn]
  cc
  {:inspect-data true})



(defn active-ent [db]
  (let [depth (:depth db)
        list (:root-list db)
        cursor (:cursor db)]
    (-> list
        (nth depth)
        (nth (nth cursor depth)))))
    

(register-sub
 ::active-entity3
 (fn [db]
   (let [depth (reaction (:depth @db))
         list (reaction (:root-list @db))
         cursor (reaction (:cursor @db))]
     (reaction (-> @list
                   (nth @depth)
                   (nth (nth @cursor @depth)))))))


(register-sub 
 ::active-entity
 (fn [db]
   (reaction (active-ent @db))))



(register-sub
 ::active-entity2
 (fn [db]
   (reaction (::active-entity @db))))



(defn add-child [db [_ conn]]
   (let [current (subscribe [::active-entity])]
     (do
;       (js/console.log @current)
       (d/transact! conn [{:db/id -1
                           :node/type :text
                          :node/text "New Node"}
                         [:db/add @current :node/children -1]])
       (dispatch [::state-from-conn conn])) 
     db))



(defn remove-node [conn eid]
  (d/transact! conn [[:db.fn/retractEntity eid]]))


(register-handler
 ::remove-node
 (fn [db [_ conn]]
   (let [eid (subscribe [::active-entity])]
     (remove-node conn @eid)
     (dispatch [::state-from-conn conn])
     db)))



(register-handler
 ::add-child
 add-child)



(register-sub
 ::edit-mode
 (fn [db]
   (reaction (::editing @db false))))



(register-handler
 ::edit-mode
 (fn [db [_ conn]]
   (let [e (subscribe [::active-entity])
         text (posh/pull conn '[*] @e)]
      (key/unbind-all!)
      (dispatch [::assoc ::editing true])
      (dispatch [::assoc ::text (:node/text @text)])
;      (js/alert (str "Editing " @e ))
      (key/bind! "enter" ::edit #(dispatch [::edit-text conn]))
      (key/bind! "esc" ::normal #(dispatch [::nav-mode conn]))
   db)))



 (register-handler
 ::edit-text
 (fn [db [_ conn]]
   (let [current (subscribe [::active-entity])
         text (subscribe [::key ::text])]
     (d/transact! conn [[:db/add @current :node/text @text]])
     (dispatch [::nav-mode conn])
     db)))    



(defn node [i e conn] 
  (let [catom (subscribe [::cursor])
        editing? (subscribe [::edit-mode])
        text  (subscribe [::key ::text])
        node (posh/pull conn '[*] e)]
    
    (fn [i e conn]
      (if (= @catom i)
        (dispatch [::assoc ::active-entity e]))
      (if (and  (= @catom i) @editing?)
        [:div
         [:input
          {:value @text
           :style {:width "100%"}
           :auto-focus "auto-focus"
           :on-change #(dispatch [::assoc ::text (-> % .-target .-value)])}]]
        [:div        
         {:style 
          {:display "flex"
           :align-items "center"
         ;  :flex-direction "row"
           :border "1px solid grey"
           :justify-content "space-between"
           :background-color (if (= @catom i)
                               "green"
                               "white")}
          :on-click #(dispatch [:move-cursor i])}
         [:div
          {:style {:max-width "50%"}}
          (:node/text @node)]
         (if-let [children (:node/children @node)]
           [:div  
            {:style {:border "1px solid red"
                    :background-color "white"
                                        ; :width "10%"
                     ;:display "flex"
                     :margin-left "auto"
;                     :flex-grow 1
                    ; :align-self "flex-end"
                     }}
            (count children)]
           #_(for [c (:node/children @node)]
             ^{:key c}[:div (pr-str c)]))]))))







(register-handler
 ::nav-mode
 (fn [db [_ conn]]
   (dispatch [::assoc ::editing false])
   (key/unbind-all!)
   (nav-keys {:left "h" :right "l" :down "j" :up "k" })
   (key/bind! "i" ::add-child  #(dispatch [::add-child conn]))
   (key/bind! "x" ::remove-node  #(dispatch [::remove-node conn]))
   (key/bind! "r" ::root #(dispatch [::set-root-to-current conn]))
   (key/bind!  "e" ::edit #(dispatch [::edit-mode conn]))
   db))





(declare text-node)

(defmulti cell-views (fn [conn node] (:node/type node :blank)))

(defmethod cell-views  :text [conn node] [text-node conn (:db/id node)])
(defmethod cell-views  :root [conn node] [:div (count @conn)])
(defmethod cell-views  :blank [] [:div "blank?"])



(s/def ::atom
  (partial instance? cljs.core/Atom))




(s/fdef cell-views :args (s/cat ::atom map?))

(s/instrument #'cell-views)






(defn cell-view2 [conn column-depth cell-index eid]
 (let [active (subscribe [::active-entity])
       e (posh/pull conn '[*] eid)] 
   
   (fn [conn column-depth cell-index eid]
     [:div {:style 
            {:padding "5px"}}
      (cell-views conn @e)
      [:button {:style 
           {:background-color 
            (if (=  eid @active)
              "green"
              "white")}
           :on-click #(dispatch [::move-cursor column-depth cell-index])}
       eid]
      [:a {:on-click #(do 
                        (dispatch [::assoc :root-eid eid])
                        (dispatch [::state-from-conn conn]))} :focus]])))





(register-handler
 ::set-root-to-current
 (fn [db [_ conn]]
   (let [ae (active-ent db)]
     (dispatch [::set-root conn ae])
     db)))



(defn column2 [conn column-index column-val]
  (let [depth (subscribe [::key :depth])]
    (fn [conn column-index column-val]
      [:div
       {:style {:flex-grow 1
                :border (if (= column-index @depth)
                          "2px solid red"
                          "1px solid black")}}
       (doall (for [[r cell] (map-indexed vector column-val)]
                ^{:key cell}[cell-view2 conn column-index r cell]))])))




(defn gridview2 [conn]
  (let [depth (subscribe [::key :depth])
        list (subscribe [::root-list])]
    (dispatch [::nav-mode conn])
    (fn [conn]
      [:div {:style {:display "flex"
                     :flex-direction "row"}}
       [:button {:on-click #(dispatch [::set-root conn 0])} 0]
       (for [[d c] (map-indexed vector @list)]
          ^{:key (str d column)} [column2 conn d c])])))



(defcard-rg grid2
  [gridview2 conn]
  cc)



(defn text-node [conn e] 
  (let [active-entity (subscribe [::active-entity])
        editing? (subscribe [::edit-mode])
        text  (subscribe [::key ::text])
        node (posh/pull conn '[*] e)]
    
    (fn [conn e]
      (if (= @active-entity e)
        (dispatch [::assoc ::active-entity e]))
      (if (and  (= @active-entity e) @editing?)
        [:div
         [:input
          {:value @text
           :style {:width "100%"}
           :auto-focus "auto-focus"
           :on-change #(dispatch [::assoc ::text (-> % .-target .-value)])}]]
        [:div        
         {:style 
          {:display "flex"
           :align-items "center"
         ;  :flex-direction "row"
           :border "1px solid grey"
           :justify-content "space-between"
           :background-color (if (= @active-entity e)
                               "green"
                               "white")}
          ;:on-click #(dispatch [::move-cursor e])
          }
         [:div
          {:style {:max-width "50%"}}
          (:node/text @node)]
         (if-let [children (:node/children @node)]
           [:div  
            {:style {:border "1px solid red"
                    :background-color "white"
                                        ; :width "10%"
                     ;:display "flex"
                     :margin-left "auto"
;                     :flex-grow 1
                    ; :align-self "flex-end"
                     }}
            (count children)]
           #_(for [c (:node/children @node)]
             ^{:key c}[:div (pr-str c)]))]))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;



(defcard mybox
"```
.box18 {
  background-color: #444;
  color: #fff;
  border-radius: 5px;
  padding: 20px;
  font-size: 150%;
}

.box18:nth-child(even) {
  background-color: #ccc;
  color: #000;
}

```" )




(defcard-rg grid4
[:div.wrapper18
 [:div {:style
        {:grid-column "2 / 4"
         :grid-row  "1 / 2" 
         :padding 20
         :background-color "#444"}} :a]
 [:div {:style
        {:grid-column "2 / 4"
         :grid-row  "4 / 50" 
         :padding 20
         :background-color "#ccc"}} :a]])





(defn toStorage
  "Puts db into localStorage"
  ([conn] (toStorage "key" conn))
  ([k conn ]
   (.setItem js/localStorage k
     (-> conn pr-str))
   conn))  


(toStorage "conn" @conn)

(def me (atom {:a "a"}))



(toStorage "a" @me)


(reset! me {:b "c"})





(defn fromStorage
  "Read in db process into a map we can merge into app-db."
  ([] (fromStorage "key"))  
  ([k]
   (->> (.getItem js/localStorage k)
        (cljs.reader/read-string)   
        ;(<- doto (println "IS SLURPED FROM LOCAL STORAGE"))
;        txn-seq->txns
        ;(<- doto (println "IS TXNS PRE-TRANSACT"))
 ;       (map #(transact! conn %))
  ;      doall
)
   ;jj@conn
))

@conn

(def mapatom {:a conn
              :b app-db})


(subscribe [::conn])








(defn reset-test []
  (let  [conn (subscribe [::conn])
         es (posh/q  @conn '[:find ?e 
                             :where [?e ?a ?v]])]
    (fn [conn]
      [:div.wrapper18
       [:button {:on-click
                 #(d/reset-conn! conn (fromStorage "conn"))}
        "reset"]
       (for [e @es]
         [:div.box18 (pr-str e)])])))


(defcard-rg crazy2
  "ghhh"
  [reset-test conn])




#_(defn transact-and-store! 
([txt]  (transact-and-store! conn txt))
([con txn]
  (->> txn (d/transact! con) :db-after !>ls)))







(defcard-rg grid18
"
```.wrapper18 {
    width: 600px;
    display: grid;
    grid-gap: 10px;
    grid-template-columns: repeat(6, 100px);
}

```
"



  [:div.wrapper18
   (for [a (range 20)]
     [:div.box18 a]
       )])




(defcard-rg grid19
"
```
.wrapper19 {
    width: 600px;
    display: grid;
    grid-gap: 10px;
    grid-template-columns: repeat(6, 100px);
    grid-template-rows: 100px 100px  100px 100px;
    grid-auto-flow: column;
```
}"


  [:div.wrapper19
   (for [a (range 20)]
     [:div.box18 a]
       )])




