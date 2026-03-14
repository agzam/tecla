(ns tecla.fsm
  "Modal keyboard FSM. Pure functions - no I/O, no side effects.

   State shape:
     {:mode       :idle | :modal
      :menu-stack [keymap ...]   ;; stack of keymap levels
      :config     {...}          ;; full tecla config
      :effect     nil | effect}  ;; side effect to execute

   Keymap entries can be:
     {:desc \"...\" :action ...}             ;; leaf - execute action
     {:desc \"...\" :keys {...}}             ;; submenu
     {:desc \"...\" :transient true :keys {}} ;; transient submenu
     :previous                               ;; go back one level
     :root                                   ;; go back to root
     :exit                                   ;; exit modal entirely")

(defn init [config]
  {:mode :idle
   :menu-stack []
   :config config
   :effect nil})

(defn current-menu
  [{:keys [menu-stack config]}]
  (if (seq menu-stack)
    (peek menu-stack)
    (:root config)))

(defn- lookup-key [state key]
  (when-let [menu (current-menu state)]
    (get menu key)))

;; ----------------------------------------------------------------
;; Transitions
;; ----------------------------------------------------------------

(defn trigger [state]
  (if (= (:mode state) :idle)
    (-> state
        (assoc :mode :modal
               :menu-stack [(get-in state [:config :root])]
               :effect {:type :enter-modal}))
    state))

(defn- exit-modal
  "Exit modal entirely - back to idle."
  [state]
  (-> state
      (assoc :mode :idle
             :menu-stack []
             :effect {:type :exit-modal})))

(defn- go-previous
  "Pop one menu level, or exit if at root."
  [state]
  (let [stack (:menu-stack state)]
    (if (< (count stack) 2)
      (exit-modal state)
      (-> state
          (update :menu-stack pop)
          (assoc :effect nil)))))

(defn- go-root
  "Jump back to root menu."
  [state]
  (-> state
      (assoc :menu-stack [(get-in state [:config :root])])
      (assoc :effect nil)))

(defn handle-key
  "Handle a key event in modal mode. Only processes key-down (press? = true)."
  [state key press?]
  (if (or (not= (:mode state) :modal) (not press?))
    (assoc state :effect nil)
    (let [entry (lookup-key state key)]
      (cond
        ;; No binding - swallow
        (nil? entry)
        (assoc state :effect nil)

        ;; Navigation keywords
        (= entry :previous) (go-previous state)
        (= entry :root)     (go-root state)
        (= entry :exit)     (exit-modal state)

        ;; Submenu - push onto stack
        (:keys entry)
        (-> state
            (update :menu-stack conj
                    (with-meta (:keys entry)
                               {:desc (:desc entry)
                                :transient (:transient entry)}))
            (assoc :effect nil))

        ;; Action leaf
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
    :trigger (trigger state)
    :key     (let [k (:key event)]
               ;; Escape always exits all modals (use :previous for back)
               (if (= :esc k)
                 (exit-modal state)
                 (handle-key state k (:press? event))))
    (assoc state :effect nil)))
