;;; tecla-tests.el --- tests for tecla.el -*- lexical-binding: t; -*-
;;
;; Copyright (C) 2026 Ag Ibragimov
;;
;; Author: Ag Ibragimov <agzam.ibragimov@gmail.com>
;; Keywords: tools tests
;; Homepage: https://github.com/agzam/tecla
;; Package-Requires: ((emacs "29"))
;;
;; This file is not part of GNU Emacs.
;;
;;; Commentary:
;;
;;  Buttercup tests for Tecla's edit-with-emacs Elisp side.
;;
;;; Code:

(require 'buttercup)
(require 'tecla)

;; ----------------------------------------------------------------
;; Helpers
;; ----------------------------------------------------------------

(defun tecla-test--cleanup-buffers ()
  "Kill all tecla-edit buffers."
  (dolist (buf (buffer-list))
    (when (string-prefix-p "*tecla-edit " (buffer-name buf))
      (kill-buffer buf))))

;; ----------------------------------------------------------------
;; tecla--escape-clj-string
;; ----------------------------------------------------------------

(describe "tecla--escape-clj-string"
  (it "passes plain text unchanged"
    (expect (tecla--escape-clj-string "hello world")
            :to-equal "hello world"))

  (it "handles empty string"
    (expect (tecla--escape-clj-string "")
            :to-equal ""))

  (it "escapes backslashes"
    (expect (tecla--escape-clj-string "a\\b")
            :to-equal "a\\\\b"))

  (it "escapes double quotes"
    (expect (tecla--escape-clj-string "say \"hi\"")
            :to-equal "say \\\"hi\\\""))

  (it "escapes newlines"
    (expect (tecla--escape-clj-string "line1\nline2")
            :to-equal "line1\\nline2"))

  (it "escapes carriage returns"
    (expect (tecla--escape-clj-string "a\rb")
            :to-equal "a\\rb"))

  (it "handles combined special characters"
    (let ((result (tecla--escape-clj-string "path\\to\n\"file\"\r")))
      (expect result :to-match "\\\\\\\\")
      (expect result :to-match "\\\\n")
      (expect result :to-match "\\\\\"")
      (expect result :to-match "\\\\r"))))

;; ----------------------------------------------------------------
;; tecla--bencode-string
;; ----------------------------------------------------------------

(describe "tecla--bencode-string"
  (it "encodes simple ASCII string"
    (expect (tecla--bencode-string "hello")
            :to-equal "5:hello"))

  (it "encodes empty string"
    (expect (tecla--bencode-string "")
            :to-equal "0:"))

  (it "uses byte-length not char-length for UTF-8"
    ;; e-acute is 2 bytes in UTF-8, but 1 character
    (let ((result (tecla--bencode-string "\u00e9")))
      (expect (string-prefix-p "2:" result) :to-equal t)
      (expect (string-bytes result) :to-equal 4)))

  (it "encodes longer strings correctly"
    (expect (tecla--bencode-string "eval")
            :to-equal "4:eval")))

;; ----------------------------------------------------------------
;; tecla--cmd
;; ----------------------------------------------------------------

(describe "tecla--cmd"
  (before-each
    (spy-on 'tecla--nrepl-eval))

  (it "generates correct sync command"
    (tecla--cmd "sync" "ewe-1" "edited text")
    (expect 'tecla--nrepl-eval :to-have-been-called-with
            "(tecla.emacs/sync-text \"ewe-1\" \"edited text\")"))

  (it "generates correct finish command"
    (tecla--cmd "finish" "ewe-1" "final text")
    (expect 'tecla--nrepl-eval :to-have-been-called-with
            "(tecla.emacs/finish-session \"ewe-1\" \"final text\")"))

  (it "generates correct cancel command"
    (tecla--cmd "cancel" "ewe-1")
    (expect 'tecla--nrepl-eval :to-have-been-called-with
            "(tecla.emacs/cancel-session \"ewe-1\")"))

  (it "escapes special characters in text"
    (tecla--cmd "sync" "ewe-1" "line1\nline2")
    (expect 'tecla--nrepl-eval :to-have-been-called-with
            "(tecla.emacs/sync-text \"ewe-1\" \"line1\\nline2\")"))

  (it "escapes backslashes in text"
    (tecla--cmd "finish" "ewe-1" "C:\\Users")
    (expect 'tecla--nrepl-eval :to-have-been-called-with
            "(tecla.emacs/finish-session \"ewe-1\" \"C:\\\\Users\")"))

  (it "escapes double quotes in text"
    (tecla--cmd "sync" "ewe-1" "say \"hi\"")
    (expect 'tecla--nrepl-eval :to-have-been-called-with
            "(tecla.emacs/sync-text \"ewe-1\" \"say \\\"hi\\\"\")"))

  (it "does nothing for unknown op"
    (tecla--cmd "unknown" "ewe-1" "text")
    (expect 'tecla--nrepl-eval :not :to-have-been-called)))

;; ----------------------------------------------------------------
;; tecla--find-buffer-by-prefix
;; ----------------------------------------------------------------

(describe "tecla--find-buffer-by-prefix"
  (after-each (tecla-test--cleanup-buffers))

  (it "finds buffer matching prefix"
    (let ((buf (get-buffer-create "*tecla-edit Test [ewe-1]*")))
      (expect (tecla--find-buffer-by-prefix "*tecla-edit Test [ewe-1]")
              :to-equal buf)))

  (it "returns nil when no match"
    (expect (tecla--find-buffer-by-prefix "*tecla-edit NonExistent [")
            :to-be nil)))

;; ----------------------------------------------------------------
;; tecla-edit-with-emacs (buffer creation)
;; ----------------------------------------------------------------

(describe "tecla-edit-with-emacs"
  (after-each (tecla-test--cleanup-buffers))

  (it "creates buffer with correct name"
    (spy-on 'tecla--nrepl-eval)
    (tecla-edit-with-emacs "brave-browser" "GitHub" "ewe-42" nil "hello")
    (expect (get-buffer "*tecla-edit GitHub [ewe-42]*") :not :to-be nil))

  (it "inserts provided text"
    (spy-on 'tecla--nrepl-eval)
    (tecla-edit-with-emacs "app" "Title" "ewe-1" nil "some content")
    (with-current-buffer "*tecla-edit Title [ewe-1]*"
      (expect (buffer-substring-no-properties (point-min) (point-max))
              :to-equal "some content")))

  (it "sets buffer-local session variables"
    (spy-on 'tecla--nrepl-eval)
    (tecla-edit-with-emacs "brave-browser" "GitHub" "ewe-5" t "text")
    (with-current-buffer "*tecla-edit GitHub [ewe-5]*"
      (expect tecla--caller-class :to-equal "brave-browser")
      (expect tecla--caller-title :to-equal "GitHub")
      (expect tecla--session-id :to-equal "ewe-5")
      (expect tecla--selection-only :not :to-be nil)))

  (it "enables minor mode"
    (spy-on 'tecla--nrepl-eval)
    (tecla-edit-with-emacs "app" "Win" "ewe-6" nil "text")
    (with-current-buffer "*tecla-edit Win [ewe-6]*"
      (expect tecla-edit-with-emacs-mode :not :to-be nil)))

  (it "runs tecla-edit-with-emacs-hook with correct args"
    (spy-on 'tecla--nrepl-eval)
    (let ((hook-args nil))
      (add-hook 'tecla-edit-with-emacs-hook
                (lambda (buf class title)
                  (setq hook-args (list buf class title))))
      (unwind-protect
          (progn
            (tecla-edit-with-emacs "slack" "Slack - #general" "ewe-7" nil "msg")
            (expect (nth 0 hook-args) :to-match "\\*tecla-edit")
            (expect (nth 1 hook-args) :to-equal "slack")
            (expect (nth 2 hook-args) :to-equal "Slack - #general"))
        (remove-hook 'tecla-edit-with-emacs-hook
                     (car (last tecla-edit-with-emacs-hook))))))

  (it "sets header-line"
    (spy-on 'tecla--nrepl-eval)
    (tecla-edit-with-emacs "app" "MyApp" "ewe-8" nil "text")
    (with-current-buffer "*tecla-edit MyApp [ewe-8]*"
      (expect header-line-format :not :to-be nil)))

  (it "reuses existing buffer for same prefix"
    (spy-on 'tecla--nrepl-eval)
    (tecla-edit-with-emacs "app" "Win" "ewe-9" nil "first")
    (tecla-edit-with-emacs "app" "Win" "ewe-9" nil "second")
    (let ((count 0))
      (dolist (buf (buffer-list))
        (when (string-prefix-p "*tecla-edit Win [ewe-9]" (buffer-name buf))
          (setq count (1+ count))))
      (expect count :to-equal 1))
    ;; Content should be replaced
    (with-current-buffer (tecla--find-buffer-by-prefix "*tecla-edit Win [ewe-9]")
      (expect (buffer-substring-no-properties (point-min) (point-max))
              :to-equal "second")))

  (it "handles nil text gracefully"
    (spy-on 'tecla--nrepl-eval)
    (tecla-edit-with-emacs "app" "Win" "ewe-10" nil nil)
    (with-current-buffer "*tecla-edit Win [ewe-10]*"
      (expect (buffer-substring-no-properties (point-min) (point-max))
              :to-equal "")))

  (it "uses fallback names for nil title/session"
    (spy-on 'tecla--nrepl-eval)
    (tecla-edit-with-emacs "app" nil nil nil "text")
    (expect (get-buffer "*tecla-edit unknown [adhoc]*") :not :to-be nil)))

;; ----------------------------------------------------------------
;; permanent-local survival
;; ----------------------------------------------------------------

(describe "permanent-local variables"
  (after-each (tecla-test--cleanup-buffers))

  (it "survive major-mode change"
    (spy-on 'tecla--nrepl-eval)
    (tecla-edit-with-emacs "slack" "Slack" "ewe-20" t "markdown text")
    (with-current-buffer "*tecla-edit Slack [ewe-20]*"
      ;; Simulate a hook changing the major mode
      (text-mode)
      (expect tecla--session-id :to-equal "ewe-20")
      (expect tecla--caller-class :to-equal "slack")
      (expect tecla--caller-title :to-equal "Slack")
      (expect tecla--selection-only :not :to-be nil))))

;; ----------------------------------------------------------------
;; header-line
;; ----------------------------------------------------------------

(describe "tecla--set-header-line"
  (after-each (tecla-test--cleanup-buffers))

  (it "shows [selection] indicator for selection-only buffers"
    (spy-on 'tecla--nrepl-eval)
    (tecla-edit-with-emacs "app" "Win" "ewe-30" t "selected text")
    (with-current-buffer "*tecla-edit Win [ewe-30]*"
      ;; header-line-format is a list; flatten and check content
      ;; (format-mode-line returns empty in batch mode)
      (let ((flat (mapconcat (lambda (x) (or x "")) header-line-format "")))
        (expect flat :to-match "\\[selection\\]"))))

  (it "omits [selection] for full-text buffers"
    (spy-on 'tecla--nrepl-eval)
    (tecla-edit-with-emacs "app" "Win" "ewe-31" nil "full text")
    (with-current-buffer "*tecla-edit Win [ewe-31]*"
      (let ((flat (mapconcat (lambda (x) (or x "")) header-line-format "")))
        (expect flat :not :to-match "\\[selection\\]"))))

  (it "shows caller title"
    (spy-on 'tecla--nrepl-eval)
    (tecla-edit-with-emacs "app" "My Editor" "ewe-32" nil "text")
    (with-current-buffer "*tecla-edit My Editor [ewe-32]*"
      (let ((flat (mapconcat (lambda (x) (or x "")) header-line-format "")))
        (expect flat :to-match "My Editor")))))

;; ----------------------------------------------------------------
;; tecla-finish-edit-with-emacs
;; ----------------------------------------------------------------

(describe "tecla-finish-edit-with-emacs"
  (after-each (tecla-test--cleanup-buffers))

  (it "kills the buffer"
    (spy-on 'tecla--nrepl-eval)
    (tecla-edit-with-emacs "app" "Win" "ewe-40" nil "text")
    (with-current-buffer "*tecla-edit Win [ewe-40]*"
      (tecla-finish-edit-with-emacs))
    (expect (get-buffer "*tecla-edit Win [ewe-40]*") :to-be nil))

  (it "sends finish command via nREPL"
    (spy-on 'tecla--nrepl-eval)
    (tecla-edit-with-emacs "app" "Win" "ewe-41" nil "edited")
    (with-current-buffer "*tecla-edit Win [ewe-41]*"
      (tecla-finish-edit-with-emacs))
    (expect 'tecla--nrepl-eval :to-have-been-called)
    (let ((last-call (spy-calls-most-recent 'tecla--nrepl-eval)))
      (expect (car (spy-context-args last-call))
              :to-match "finish-session.*ewe-41")))

  (it "runs before-finish hook with buffer name and class"
    (spy-on 'tecla--nrepl-eval)
    (let ((hook-args nil))
      (add-hook 'tecla-before-finish-edit-with-emacs-hook
                (lambda (buf-name class)
                  (setq hook-args (list buf-name class))))
      (unwind-protect
          (progn
            (tecla-edit-with-emacs "brave" "Tab" "ewe-42" nil "text")
            (with-current-buffer "*tecla-edit Tab [ewe-42]*"
              (tecla-finish-edit-with-emacs))
            (expect (nth 0 hook-args) :to-match "\\*tecla-edit")
            (expect (nth 1 hook-args) :to-equal "brave"))
        (remove-hook 'tecla-before-finish-edit-with-emacs-hook
                     (car (last tecla-before-finish-edit-with-emacs-hook))))))

  (it "copies to clipboard when no session-id"
    (spy-on 'tecla--nrepl-eval)
    (spy-on 'clipboard-kill-ring-save)
    (tecla-edit-with-emacs "app" "Win" nil nil "fallback text")
    (with-current-buffer (tecla--find-buffer-by-prefix "*tecla-edit Win [")
      (tecla-finish-edit-with-emacs))
    (expect 'clipboard-kill-ring-save :to-have-been-called)
    (expect 'tecla--nrepl-eval :not :to-have-been-called)))

;; ----------------------------------------------------------------
;; tecla-cancel-edit-with-emacs
;; ----------------------------------------------------------------

(describe "tecla-cancel-edit-with-emacs"
  (after-each (tecla-test--cleanup-buffers))

  (it "kills the buffer"
    (spy-on 'tecla--nrepl-eval)
    (tecla-edit-with-emacs "app" "Win" "ewe-50" nil "text")
    (with-current-buffer "*tecla-edit Win [ewe-50]*"
      (tecla-cancel-edit-with-emacs))
    (expect (get-buffer "*tecla-edit Win [ewe-50]*") :to-be nil))

  (it "sends cancel command via nREPL"
    (spy-on 'tecla--nrepl-eval)
    (tecla-edit-with-emacs "app" "Win" "ewe-51" nil "text")
    (with-current-buffer "*tecla-edit Win [ewe-51]*"
      (tecla-cancel-edit-with-emacs))
    (expect 'tecla--nrepl-eval :to-have-been-called)
    (let ((last-call (spy-calls-most-recent 'tecla--nrepl-eval)))
      (expect (car (spy-context-args last-call))
              :to-match "cancel-session.*ewe-51")))

  (it "runs before-cancel hook"
    (spy-on 'tecla--nrepl-eval)
    (let ((hook-args nil))
      (add-hook 'tecla-before-cancel-edit-with-emacs-hook
                (lambda (buf-name class)
                  (setq hook-args (list buf-name class))))
      (unwind-protect
          (progn
            (tecla-edit-with-emacs "firefox" "Tab" "ewe-52" nil "text")
            (with-current-buffer "*tecla-edit Tab [ewe-52]*"
              (tecla-cancel-edit-with-emacs))
            (expect (nth 0 hook-args) :to-match "\\*tecla-edit")
            (expect (nth 1 hook-args) :to-equal "firefox"))
        (remove-hook 'tecla-before-cancel-edit-with-emacs-hook
                     (car (last tecla-before-cancel-edit-with-emacs-hook))))))

  (it "does not send nREPL when no session-id"
    (spy-on 'tecla--nrepl-eval)
    (tecla-edit-with-emacs "app" "Win" nil nil "text")
    (with-current-buffer (tecla--find-buffer-by-prefix "*tecla-edit Win [")
      (tecla-cancel-edit-with-emacs))
    (expect 'tecla--nrepl-eval :not :to-have-been-called)))

;; ----------------------------------------------------------------
;; tecla-sync-edit-with-emacs
;; ----------------------------------------------------------------

(describe "tecla-sync-edit-with-emacs"
  (after-each (tecla-test--cleanup-buffers))

  (it "keeps the buffer alive"
    (spy-on 'tecla--nrepl-eval)
    (tecla-edit-with-emacs "app" "Win" "ewe-60" nil "text")
    (with-current-buffer "*tecla-edit Win [ewe-60]*"
      (tecla-sync-edit-with-emacs))
    (expect (get-buffer "*tecla-edit Win [ewe-60]*") :not :to-be nil))

  (it "sends sync command via nREPL"
    (spy-on 'tecla--nrepl-eval)
    (tecla-edit-with-emacs "app" "Win" "ewe-61" nil "synced")
    (with-current-buffer "*tecla-edit Win [ewe-61]*"
      (tecla-sync-edit-with-emacs))
    (expect 'tecla--nrepl-eval :to-have-been-called)
    (let ((last-call (spy-calls-most-recent 'tecla--nrepl-eval)))
      (expect (car (spy-context-args last-call))
              :to-match "sync-text.*ewe-61")))

  (it "sends current buffer content"
    (spy-on 'tecla--nrepl-eval)
    (tecla-edit-with-emacs "app" "Win" "ewe-62" nil "original")
    (with-current-buffer "*tecla-edit Win [ewe-62]*"
      (erase-buffer)
      (insert "modified content")
      (tecla-sync-edit-with-emacs))
    (let ((last-call (spy-calls-most-recent 'tecla--nrepl-eval)))
      (expect (car (spy-context-args last-call))
              :to-match "modified content")))

  (it "runs after-sync hook"
    (spy-on 'tecla--nrepl-eval)
    (let ((hook-args nil))
      (add-hook 'tecla-after-sync-hook
                (lambda (buf-name session-id)
                  (setq hook-args (list buf-name session-id))))
      (unwind-protect
          (progn
            (tecla-edit-with-emacs "app" "Win" "ewe-63" nil "text")
            (with-current-buffer "*tecla-edit Win [ewe-63]*"
              (tecla-sync-edit-with-emacs))
            (expect (nth 0 hook-args) :to-match "\\*tecla-edit")
            (expect (nth 1 hook-args) :to-equal "ewe-63"))
        (remove-hook 'tecla-after-sync-hook
                     (car (last tecla-after-sync-hook))))))

  (it "errors when no session-id"
    (spy-on 'tecla--nrepl-eval)
    (tecla-edit-with-emacs "app" "Win" nil nil "text")
    (with-current-buffer (tecla--find-buffer-by-prefix "*tecla-edit Win [")
      (expect (tecla-sync-edit-with-emacs) :to-throw 'user-error))))

;; ----------------------------------------------------------------
;; minor mode keymap
;; ----------------------------------------------------------------

(describe "tecla-edit-with-emacs-mode keymap"
  (after-each (tecla-test--cleanup-buffers))

  (it "binds C-c C-c to finish"
    (expect (lookup-key tecla-edit-with-emacs-mode-map (kbd "C-c C-c"))
            :to-equal #'tecla-finish-edit-with-emacs))

  (it "binds C-c C-s to sync"
    (expect (lookup-key tecla-edit-with-emacs-mode-map (kbd "C-c C-s"))
            :to-equal #'tecla-sync-edit-with-emacs))

  (it "binds C-c C-k to cancel"
    (expect (lookup-key tecla-edit-with-emacs-mode-map (kbd "C-c C-k"))
            :to-equal #'tecla-cancel-edit-with-emacs)))

;; Local Variables:
;; package-lint-main-file: "tecla.el"
;; End:
;;; tecla-tests.el ends here
