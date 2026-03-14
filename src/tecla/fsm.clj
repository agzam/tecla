(ns tecla.fsm
  "Modal keyboard FSM. Pure functions - no I/O, no side effects.

   State shape:
     {:mode       :idle | :modal
      :menu-stack [keymap ...]   ;; stack of keymap levels
      :config     {...}          ;; full tecla config
      :effect     nil | effect}  ;; side effect to execute

   Effects (returned, not executed):
     {:type :action :action ...}   ;; execute an action
     {:type :enter-modal}          ;; bridge should enter modal
     {:type :exit-modal}           ;; bridge should exit modal
     {:type :emit-key :key ... :value ...}")

(defn init
  "Create initial FSM state from config."
  [config]
  {:mode :idle
   :menu-stack []
   :config config
   :effect nil})

(defn current-menu
  "Return the currently active keymap level, or nil if not in modal."
  [{:keys [menu-stack config]}]
  (if (seq menu-stack)
    (peek menu-stack)
    (:root config)))

(defn- lookup-key
  "Look up a key in the current menu. Returns the entry map or nil."
  [state key]
  (when-let [menu (current-menu state)]
    (get menu key)))

;; ----------------------------------------------------------------
;; Transitions
;; ----------------------------------------------------------------

(defn trigger
  "Handle modal trigger activation. Transitions :idle -> :modal."
  [state]
  (if (= (:mode state) :idle)
    (-> state
        (assoc :mode :modal
               :menu-stack [(get-in state [:config :root])]
               :effect {:type :enter-modal}))
    state))

(defn escape
  "Handle escape / Ctrl+g. Pops menu stack or exits modal."
  [state]
  (if (not= (:mode state) :modal)
    state
    (let [stack (:menu-stack state)]
      (if (< (count stack) 2)
        ;; At root level - exit modal entirely
        (-> state
            (assoc :mode :idle
                   :menu-stack []
                   :effect {:type :exit-modal}))
        ;; Pop one level
        (-> state
            (update :menu-stack pop)
            (assoc :effect nil))))))

(defn handle-key
  "Handle a key event in modal mode. Returns updated state.
   Only processes key-down events (press? = true)."
  [state key press?]
  (if (or (not= (:mode state) :modal) (not press?))
    (assoc state :effect nil)
    (let [entry (lookup-key state key)]
      (cond
        ;; No binding - swallow (no effect)
        (nil? entry)
        (assoc state :effect nil)

        ;; Submenu - push onto stack (carry :transient flag as metadata)
        (:keys entry)
        (-> state
            (update :menu-stack conj
                    (with-meta (:keys entry)
                               {:transient (:transient entry)}))
            (assoc :effect nil))

        ;; Action leaf - execute action
        (:action entry)
        (let [;; Transient if the action itself or any parent submenu is transient
              transient? (or (:transient entry)
                             (some #(:transient (meta %)) (:menu-stack state)))]
          (if transient?
            (assoc state :effect {:type :action :action (:action entry)})
            (-> state
                (assoc :mode :idle :menu-stack [])
                (assoc :effect {:type :action-and-exit
                                :action (:action entry)}))))

        ;; Unrecognized entry shape
        :else
        (assoc state :effect nil)))))

(defn process-event
  "Main entry point. Process a bridge event, return updated FSM state.
   Event shape: {:type :trigger | :key, :key keyword, :press? bool}"
  [state event]
  (case (:type event)
    :trigger (trigger state)
    :key     (let [k (:key event)]
               (if (#{:esc} k)
                 (escape state)
                 (handle-key state k (:press? event))))
    ;; Unknown event type - no-op
    (assoc state :effect nil)))
