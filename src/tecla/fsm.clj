(ns tecla.fsm
  "Modal keyboard FSM. Pure functions - no I/O, no side effects.

   State shape:
     {:mode       :idle | :modal
      :menu-stack [keymap ...]
      :config     {...}
      :effect     nil | effect}

   Keymap entries can be:
     {:desc ... :action ...}           ;; leaf
     {:desc ... :keys {...}}           ;; submenu
     {:desc ... :transient true :keys {}} ;; transient submenu
     :previous                         ;; go back one level
     :root                             ;; jump to root of current modal
     :exit                             ;; exit modal entirely")

(defn init [config]
  {:mode :idle
   :menu-stack []
   :config config
   :effect nil})

(defn current-menu
  [{:keys [menu-stack]}]
  (when (seq menu-stack)
    (peek menu-stack)))

(defn- lookup-key [state key]
  (when-let [menu (current-menu state)]
    (get menu key)))

;; ----------------------------------------------------------------
;; Transitions
;; ----------------------------------------------------------------

(defn enter-modal
  "Enter modal with the given submenu entry."
  [state entry]
  (if (= (:mode state) :idle)
    (-> state
        (assoc :mode :modal
               :menu-stack [(with-meta (:keys entry)
                                       {:desc (:desc entry)
                                        :transient (:transient entry)})]
               :effect {:type :enter-modal}))
    state))

(defn- exit-modal [state]
  (-> state
      (assoc :mode :idle :menu-stack []
             :effect {:type :exit-modal})))

(defn- go-previous [state]
  (let [stack (:menu-stack state)]
    (if (< (count stack) 2)
      (exit-modal state)
      (-> state
          (update :menu-stack pop)
          (assoc :effect nil)))))

(defn- go-root [state]
  (let [stack (:menu-stack state)]
    (if (seq stack)
      (-> state
          (assoc :menu-stack [(first stack)])
          (assoc :effect nil))
      state)))

(defn handle-key
  [state key press?]
  (if (or (not= (:mode state) :modal) (not press?))
    (assoc state :effect nil)
    (let [entry (lookup-key state key)]
      (cond
        (nil? entry)
        (assoc state :effect nil)

        (= entry :previous) (go-previous state)
        (= entry :root)     (go-root state)
        (= entry :exit)     (exit-modal state)

        (:keys entry)
        (-> state
            (update :menu-stack conj
                    (with-meta (:keys entry)
                               {:desc (:desc entry)
                                :transient (:transient entry)}))
            (assoc :effect nil))

        (:action entry)
        (let [transient? (or (:transient entry)
                             (some #(:transient (meta %)) (:menu-stack state)))]
          (if transient?
            (assoc state :effect {:type :action :action (:action entry)})
            (-> state
                (assoc :mode :idle :menu-stack [])
                (assoc :effect {:type :action-and-exit
                                :action (:action entry)}))))

        :else
        (assoc state :effect nil)))))

(defn process-event [state event]
  (case (:type event)
    :enter-modal (enter-modal state (:entry event))
    :key (let [k (:key event)]
           (if (= :esc k)
             (exit-modal state)
             (handle-key state k (:press? event))))
    (assoc state :effect nil)))
