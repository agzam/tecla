#!/usr/bin/env gjs
//
// tecla which-key HUD - GTK4 overlay.
// Reads JSON commands from stdin, one per line:
//   {"show":true, "name":"Apps", "keys":[{"key":"e","desc":"Emacs"}, ...]}
//   {"show":false}
//   {"quit":true}
//
// Uses gtk4-layer-shell when available (wlr compositors),
// falls back to a plain undecorated window elsewhere (GNOME).

imports.gi.versions.Gtk = '4.0';
const { Gtk, GLib, Gdk, Gio } = imports.gi;

// Try loading gtk4-layer-shell for wlr compositors
let LayerShell = null;
try {
    imports.gi.versions.Gtk4LayerShell = '1.0';
    LayerShell = imports.gi.Gtk4LayerShell;
} catch (_) {}

// ----------------------------------------------------------------

const CSS = `
window.tecla-hud {
    background-color: rgba(30, 30, 46, 0.92);
    border-radius: 10px;
}
.tecla-inner {
    padding: 14px 18px;
}
.tecla-title {
    color: #cdd6f4;
    font-size: 18px;
    font-weight: bold;
    margin-bottom: 10px;
}
.tecla-key {
    color: #89b4fa;
    font-family: monospace;
    font-size: 16px;
}
.tecla-desc {
    color: #bac2de;
    font-size: 15px;
}
`;

// ----------------------------------------------------------------

const app = new Gtk.Application({ application_id: 'dev.tecla.hud' });
let win = null;
let titleLabel = null;
let keysBox = null;

function buildUI() {
    const provider = new Gtk.CssProvider();
    provider.load_from_string(CSS);
    Gtk.StyleContext.add_provider_for_display(
        Gdk.Display.get_default(), provider,
        Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION);

    win = new Gtk.Window({
        application: app,
        title: 'tecla',
        decorated: false,
        resizable: false,
        default_width: 280,
    });
    win.add_css_class('tecla-hud');

    if (LayerShell) {
        LayerShell.init_for_window(win);
        LayerShell.set_layer(win, LayerShell.Layer.OVERLAY);
        LayerShell.set_anchor(win, LayerShell.Edge.TOP, true);
        LayerShell.set_anchor(win, LayerShell.Edge.LEFT, true);
        LayerShell.set_margin(win, LayerShell.Edge.TOP, 30);
        LayerShell.set_margin(win, LayerShell.Edge.LEFT, 30);
        LayerShell.set_keyboard_mode(win, LayerShell.KeyboardMode.NONE);
    }

    let outer = new Gtk.Box({ orientation: Gtk.Orientation.VERTICAL });
    outer.add_css_class('tecla-inner');

    titleLabel = new Gtk.Label({ label: '', halign: Gtk.Align.START });
    titleLabel.add_css_class('tecla-title');
    outer.append(titleLabel);

    keysBox = new Gtk.Grid({ row_spacing: 3, column_spacing: 12 });
    outer.append(keysBox);

    win.set_child(outer);
    win.set_visible(false);
}

function clearKeys() {
    // Grid has no remove-all; walk children
    let child = keysBox.get_first_child();
    while (child) {
        let next = child.get_next_sibling();
        keysBox.remove(child);
        child = next;
    }
}

function handleCommand(obj) {
    if (obj.quit) { app.quit(); return; }

    if (obj.show === false) {
        if (win) win.set_visible(false);
        return;
    }

    if (obj.show && win) {
        titleLabel.set_label(obj.name || '');
        clearKeys();
        let row = 0;
        for (let item of (obj.keys || [])) {
            let k = new Gtk.Label({
                label: item.key || '', halign: Gtk.Align.START
            });
            k.add_css_class('tecla-key');
            let d = new Gtk.Label({
                label: item.desc || '', halign: Gtk.Align.START
            });
            d.add_css_class('tecla-desc');
            keysBox.attach(k, 0, row, 1, 1);
            keysBox.attach(d, 1, row, 1, 1);
            row++;
        }
        win.set_visible(true);
        // present() needed for initial map; layer-shell ignores focus
        if (LayerShell) win.present();
    }
}

// ----------------------------------------------------------------
// Stdin reader (async, integrates with GLib main loop)
// ----------------------------------------------------------------

function startStdinReader() {
    let UnixInputStream;
    try { UnixInputStream = imports.gi.GioUnix.InputStream; }
    catch (_) { UnixInputStream = Gio.UnixInputStream; }
    const stdin = new UnixInputStream({ fd: 0, close_fd: false });
    const stream = new Gio.DataInputStream({ base_stream: stdin });

    function readNext() {
        stream.read_line_async(GLib.PRIORITY_DEFAULT, null,
            (src, result) => {
                try {
                    let [bytes] = src.read_line_finish(result);
                    if (bytes === null) { app.quit(); return; }
                    let line = (bytes instanceof Uint8Array)
                        ? new TextDecoder().decode(bytes)
                        : bytes.toString();
                    line = line.trim();
                    if (line) handleCommand(JSON.parse(line));
                } catch (e) {
                    log('tecla-hud: ' + e.message);
                }
                readNext();
            });
    }
    readNext();
}

// ----------------------------------------------------------------

app.connect('activate', () => {
    buildUI();
    startStdinReader();
    log('tecla-hud: ready' + (LayerShell ? ' (layer-shell)' : ' (fallback)'));
});

app.run([]);
