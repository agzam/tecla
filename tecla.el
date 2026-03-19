;;; tecla.el --- Tecla Elisp Helpers -*- lexical-binding: t; -*-
;;
;; Copyright (C) 2025 Ag Ibragimov
;;
;; Author: Ag Ibragimov <agzam.ibragimov@gmail.com>
;; Maintainer: Ag Ibragimov <agzam.ibragimov@gmail.com>
;; Version: 0.1.0
;; Keywords: extensions tools
;; Homepage: https://github.com/agzam/tecla
;; Package-Requires: ((emacs "27"))
;;
;; This file is not part of GNU Emacs.
;;
;;; Commentary:
;;
;; Emacs helpers for Tecla's edit-with-emacs feature.
;;
;; Tecla runs as a Babashka process on Linux, capturing keyboard
;; input via evdev/uinput.  When the user triggers edit-with-emacs,
;; Tecla copies the focused app's text and calls emacsclient to open
;; an editing buffer.  This package provides the buffer, minor mode,
;; and IPC back to Tecla via its nREPL server (bencode protocol).
;;
;; Usage:
;;   (require 'tecla)
;;   (tecla-global-edit-with-emacs-mode +1)
;;
;;; Code:

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Customization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defgroup tecla nil
  "Tecla keyboard toolkit integration."
  :group 'tools
  :prefix "tecla-")

(defcustom tecla-nrepl-port 7890
  "Port of Tecla's nREPL server."
  :type 'integer
  :group 'tecla)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Buffer-local session variables
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defvar-local tecla--caller-class nil
  "Window class of the app that invoked this edit buffer.")

(defvar-local tecla--session-id nil
  "Unique session ID linking this buffer to a Tecla session.")

(defvar-local tecla--selection-only nil
  "Non-nil if this buffer edits only the selected text.")

(defvar-local tecla--caller-title nil
  "Title of the app window that invoked this edit buffer.")

;; Survive major-mode changes (e.g., when hooks set markdown-mode)
(put 'tecla--caller-class 'permanent-local t)
(put 'tecla--session-id 'permanent-local t)
(put 'tecla--selection-only 'permanent-local t)
(put 'tecla--caller-title 'permanent-local t)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Minor mode
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defvar tecla-edit-with-emacs-mode-map
  (let ((map (make-sparse-keymap)))
    (define-key map (kbd "C-c C-c") #'tecla-finish-edit-with-emacs)
    (define-key map (kbd "C-c C-k") #'tecla-cancel-edit-with-emacs)
    (define-key map (kbd "C-c C-s") #'tecla-sync-edit-with-emacs)
    map)
  "Keymap for `tecla-edit-with-emacs-mode'.")

(define-minor-mode tecla-edit-with-emacs-mode
  "Minor mode for buffers opened by Tecla's edit-with-emacs.

\\{tecla-edit-with-emacs-mode-map}"
  :init-value nil
  :lighter " ewe"
  :keymap tecla-edit-with-emacs-mode-map
  :group 'tecla)

(defun tecla--turn-on-edit-with-emacs-mode ()
  "Enable `tecla-edit-with-emacs-mode' in matching buffers."
  (when (string-match-p "\\*tecla-edit " (buffer-name (current-buffer)))
    (tecla-edit-with-emacs-mode +1)))

(define-global-minor-mode tecla-global-edit-with-emacs-mode
  tecla-edit-with-emacs-mode tecla--turn-on-edit-with-emacs-mode
  :group 'tecla)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Hooks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defvar tecla-edit-with-emacs-hook nil
  "Hook run when an edit-with-emacs buffer is created.
Functions receive: BUFFER-NAME, CLASS, TITLE.")

(defvar tecla-before-finish-edit-with-emacs-hook nil
  "Hook run before finishing (C-c C-c).
Functions receive: BUFFER-NAME, CLASS.")

(defvar tecla-before-cancel-edit-with-emacs-hook nil
  "Hook run before canceling (C-c C-k).
Functions receive: BUFFER-NAME, CLASS.")

(defvar tecla-after-sync-hook nil
  "Hook run after syncing text (C-c C-s).
Functions receive: BUFFER-NAME, SESSION-ID.")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; nREPL IPC (bencode)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defun tecla--bencode-string (s)
  "Bencode-encode string S (UTF-8 byte-length prefixed)."
  (let ((utf8 (encode-coding-string s 'utf-8)))
    (concat (number-to-string (length utf8)) ":" utf8)))

(defun tecla--nrepl-eval (code)
  "Evaluate Clojure CODE in Tecla's nREPL server.
Fire-and-forget - does not wait for a response."
  (condition-case err
      (let* ((msg (concat "d"
                          (tecla--bencode-string "op")
                          (tecla--bencode-string "eval")
                          (tecla--bencode-string "code")
                          (tecla--bencode-string code)
                          "e"))
             (proc (make-network-process
                    :name "tecla-nrepl"
                    :host "127.0.0.1"
                    :service tecla-nrepl-port
                    :family 'ipv4
                    :coding 'binary
                    :noquery t)))
        (unwind-protect
            (process-send-string proc msg)
          (delete-process proc)))
    (error
     (message "Tecla: cannot connect to nREPL (%s:%d): %s"
              "127.0.0.1" tecla-nrepl-port
              (error-message-string err)))))

(defun tecla--escape-clj-string (s)
  "Escape S for embedding in a Clojure string literal."
  (replace-regexp-in-string
   "\n" "\\\\n"
   (replace-regexp-in-string
    "\r" "\\\\r"
    (replace-regexp-in-string
     "\"" "\\\\\""
     (replace-regexp-in-string "\\\\" "\\\\\\\\" s)))))

(defun tecla--cmd (op session-id &optional text)
  "Send command OP for SESSION-ID to Tecla via nREPL.
TEXT is the buffer content (required for sync/finish, ignored for cancel)."
  (let ((code
         (pcase op
           ("sync"
            (format "(tecla.emacs/sync-text \"%s\" \"%s\")"
                    session-id (tecla--escape-clj-string text)))
           ("finish"
            (format "(tecla.emacs/finish-session \"%s\" \"%s\")"
                    session-id (tecla--escape-clj-string text)))
           ("cancel"
            (format "(tecla.emacs/cancel-session \"%s\")"
                    session-id)))))
    (when code
      (tecla--nrepl-eval code))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defun tecla--find-buffer-by-prefix (prefix)
  "Find the first buffer whose name starts with PREFIX."
  (seq-find (lambda (buf) (string-prefix-p prefix (buffer-name buf)))
            (buffer-list)))

(defun tecla--buffer-text ()
  "Return buffer text as a string."
  (buffer-substring-no-properties (point-min) (point-max)))

(defun tecla--key-for (cmd)
  "Human-readable key description for CMD."
  (let ((key (where-is-internal cmd tecla-edit-with-emacs-mode-map t)))
    (if key (key-description key) "???")))

(defun tecla--set-header-line ()
  "Set `header-line-format' showing keybindings for the edit session."
  (setq header-line-format
        (list
         (propertize (format " %s " (or tecla--caller-title "Edit"))
                     'face '(:weight bold :inherit font-lock-function-name-face))
         (when tecla--selection-only
           (propertize " [selection] " 'face '(:inherit warning)))
         (propertize " | " 'face 'shadow)
         (propertize (tecla--key-for #'tecla-finish-edit-with-emacs)
                     'face '(:weight bold :inherit success))
         (propertize " submit " 'face 'shadow)
         (propertize "| " 'face 'shadow)
         (propertize (tecla--key-for #'tecla-sync-edit-with-emacs)
                     'face '(:weight bold :inherit font-lock-constant-face))
         (propertize " sync " 'face 'shadow)
         (propertize "| " 'face 'shadow)
         (propertize (tecla--key-for #'tecla-cancel-edit-with-emacs)
                     'face '(:weight bold :inherit error))
         (propertize " cancel" 'face 'shadow))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Core: edit-with-emacs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;###autoload
(defun tecla-edit-with-emacs (&optional class title session-id selection-only text)
  "Edit text from another app with Emacs.

CLASS - window class of the caller app.
TITLE - title of the app window.
SESSION-ID - unique session identifier from Tecla.
SELECTION-ONLY - non-nil if editing only the selected text.
TEXT - captured text to edit (passed directly, not read from clipboard)."
  (let* ((buf-name (format "*tecla-edit %s [%s]*"
                           (or title "unknown")
                           (or session-id "adhoc")))
         (prefix (format "*tecla-edit %s [%s]"
                         (or title "")
                         (or session-id "")))
         (buffer (or (tecla--find-buffer-by-prefix prefix)
                     (get-buffer-create buf-name))))
    (unless (bound-and-true-p tecla-global-edit-with-emacs-mode)
      (tecla-global-edit-with-emacs-mode +1))
    (with-current-buffer buffer
      (erase-buffer)
      (setq-local tecla--caller-class class)
      (setq-local tecla--caller-title title)
      (setq-local tecla--session-id session-id)
      (setq-local tecla--selection-only selection-only)
      (when text (insert text))
      (deactivate-mark)
      (goto-char (point-min))
      (tecla-edit-with-emacs-mode +1))
    (pop-to-buffer buffer)
    (run-hook-with-args 'tecla-edit-with-emacs-hook buf-name class title)
    ;; Set header-line last - hooks may change major mode
    (tecla--set-header-line)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Sync
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defun tecla-sync-edit-with-emacs ()
  "Push buffer text to the originating app without ending the session."
  (interactive)
  (let ((session-id tecla--session-id)
        (text (tecla--buffer-text)))
    (unless session-id
      (user-error "No session ID - cannot sync"))
    (tecla--cmd "sync" session-id text)
    (message "Synced to %s" (or tecla--caller-title "app"))
    (run-hook-with-args 'tecla-after-sync-hook
                        (buffer-name) session-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Finish
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defun tecla-finish-edit-with-emacs ()
  "Send text to originating app and close the buffer."
  (interactive)
  (let ((class tecla--caller-class)
        (session-id tecla--session-id)
        (text (tecla--buffer-text)))
    (run-hook-with-args
     'tecla-before-finish-edit-with-emacs-hook
     (buffer-name (current-buffer)) class)
    (if session-id
        (progn
          (if (one-window-p)
              (kill-buffer)
            (kill-buffer-and-window))
          (tecla--cmd "finish" session-id text))
      ;; No session - just copy to clipboard and close
      (progn
        (clipboard-kill-ring-save (point-min) (point-max))
        (if (one-window-p)
            (kill-buffer)
          (kill-buffer-and-window))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Cancel
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defun tecla-cancel-edit-with-emacs ()
  "Cancel editing and return to the originating app."
  (interactive)
  (let ((class tecla--caller-class)
        (session-id tecla--session-id))
    (run-hook-with-args
     'tecla-before-cancel-edit-with-emacs-hook
     (buffer-name (current-buffer)) class)
    (if (one-window-p)
        (kill-buffer)
      (kill-buffer-and-window))
    (when session-id
      (tecla--cmd "cancel" session-id))))

(provide 'tecla)

;;; tecla.el ends here
