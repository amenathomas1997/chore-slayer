/* =========================================================================
   Chore & Activity Tracker — front-end app (vanilla JS, no build step).
   ========================================================================= */
(function () {
    'use strict';

    // -------------------------------------------------- small DOM helper
    function el(tag, props, children) {
        const node = document.createElement(tag);
        props = props || {};
        for (const k in props) {
            const v = props[k];
            if (v === null || v === undefined || v === false) continue;
            if (k === 'class') node.className = v;
            else if (k === 'text') node.textContent = v;
            else if (k === 'html') node.innerHTML = v;
            else if (k === 'dataset') Object.assign(node.dataset, v);
            else if (k.slice(0, 2) === 'on' && typeof v === 'function')
                node.addEventListener(k.slice(2).toLowerCase(), v);
            else if (v === true) node.setAttribute(k, '');
            else node.setAttribute(k, v);
        }
        const kids = [].concat(children === undefined ? [] : children);
        for (const c of kids) {
            if (c === null || c === undefined || c === false) continue;
            node.appendChild(typeof c === 'object' ? c : document.createTextNode(String(c)));
        }
        return node;
    }
    const $ = (sel) => document.querySelector(sel);

    // -------------------------------------------------- API wrapper
    const TOKEN_KEY = 'chore_parent_token';
    const CHILD_KEY = 'chore_child';
    const FAMILY_KEY = 'chore_family';
    // The parent sign-in is kept in sessionStorage, so closing the tab or app
    // ends it; it is never left on the device for a kid to find. Older versions
    // kept it in localStorage, so clear any copy left there.
    try { localStorage.removeItem(TOKEN_KEY); } catch (e) { /* ignore */ }
    const getToken = () => sessionStorage.getItem(TOKEN_KEY);
    const setToken = (t) => {
        if (t) { sessionStorage.setItem(TOKEN_KEY, t); markParentActivity(); }
        else sessionStorage.removeItem(TOKEN_KEY);
    };
    // Lock the parent area after this long without a tap or key press.
    const PARENT_IDLE_MS = 10 * 60 * 1000;
    let parentLastActive = Date.now();
    function markParentActivity() { parentLastActive = Date.now(); }
    const getFamily = () => { try { return JSON.parse(localStorage.getItem(FAMILY_KEY) || 'null'); } catch (e) { return null; } };
    const setFamily = (f) => {
        if (f) localStorage.setItem(FAMILY_KEY, JSON.stringify(f));
        else localStorage.removeItem(FAMILY_KEY);
        state.family = f || null;   // keep in-memory state in sync so view routing is correct
    };

    async function api(action, opts) {
        opts = opts || {};
        const params = Object.assign({ action: action }, opts.params || {});
        const qs = new URLSearchParams(params).toString();
        const init = { method: opts.method || 'GET', headers: {} };
        const token = getToken();
        if (token) init.headers['Authorization'] = 'Bearer ' + token;
        if (opts.body) {
            init.headers['Content-Type'] = 'application/json';
            init.body = JSON.stringify(opts.body);
        }
        const res = await fetch('api.php?' + qs, init);
        let data = {};
        try { data = await res.json(); } catch (e) { /* non-JSON */ }
        if (!res.ok) {
            const err = new Error(data.message || 'Something went wrong.');
            err.code = data.error; err.status = res.status;
            if (res.status === 401) { setToken(null); }
            throw err;
        }
        return data;
    }

    // -------------------------------------------------- app state
    const state = {
        view: 'login',
        loginTab: 'kid',
        family: null,       // { id, code, name } once this device is paired to a family
        today: '',
        child: null,        // logged-in kid
        kidData: null,      // today's day_summary for the kid
        parentTab: 'children',
        children: [],       // active children (parent view cache)
        selectedChildId: '',
        bhis: null,
    };

    // -------------------------------------------------- UI utilities
    let toastTimer = null;
    function toast(msg, kind) {
        const t = $('#toast');
        t.textContent = msg;
        t.className = 'toast' + (kind ? ' toast--' + kind : '');
        t.hidden = false;
        announce(kind === 'error' ? 'Error: ' + msg : msg);
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => { t.hidden = true; }, kind === 'error' ? 7000 : 3800);
    }
    function announce(msg) { $('#sr-status').textContent = msg; }

    // Validation error tied to a field: marks it invalid, shows an inline message
    // the field references (aria-describedby), and moves focus to it so a screen
    // reader reads the field + its error (WCAG 3.3.1 Error Identification / 3.3.3).
    let _errSeq = 0;
    function fieldError(input, msg) {
        if (!input) { toast(msg, 'error'); return; }
        if (!input.id) input.id = 'fld-' + (++_errSeq);
        const errId = input.id + '-err';
        let errEl = document.getElementById(errId);
        if (!errEl) {
            errEl = el('p', { id: errId, class: 'field-err' });
            input.setAttribute('aria-describedby', errId);
            input.insertAdjacentElement('afterend', errEl);
        }
        input.setAttribute('aria-invalid', 'true');
        errEl.textContent = msg;
        input.focus();
    }

    function fmtDate(ymd) {
        if (!ymd) return '';
        const p = ymd.split('-');
        const d = new Date(+p[0], +p[1] - 1, +p[2]);
        return d.toLocaleDateString(undefined,
            { weekday: 'long', month: 'long', day: 'numeric' });
    }
    function fmtDateShort(ymd) {
        if (!ymd) return '';
        const p = ymd.split('-');
        const d = new Date(+p[0], +p[1] - 1, +p[2]);
        return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
    }
    function daysAgoStr(n) {
        const p = state.today.split('-');
        const d = new Date(+p[0], +p[1] - 1, +p[2]);
        d.setDate(d.getDate() - n);
        const mm = String(d.getMonth() + 1).padStart(2, '0');
        const dd = String(d.getDate()).padStart(2, '0');
        return d.getFullYear() + '-' + mm + '-' + dd;
    }

    const EMOJIS = ['🙂','😀','😎','🦄','🐶','🐱','🦊','🐸','🦁','🐧','🦋','🌟','🚀','⚽','🎨','🎸','📚','🌈','🍎','🦖'];
    const COLORS = ['#5b8def','#e0556f','#2e9e6b','#e08a1e','#8b5cf6','#0ea5b7','#d2543f','#7a8a3a'];
    const MOODS = [
        { v: 'great', label: '😄 Great' },
        { v: 'good',  label: '🙂 Good' },
        { v: 'ok',    label: '😐 Okay' },
        { v: 'rough', label: '😟 Rough' },
    ];

    // Time-of-day slots (must match the server's SLOTS order).
    const SLOTS = [
        { v: 'morning',   label: 'Morning',   emoji: '🌅' },
        { v: 'midday',    label: 'Midday',    emoji: '🌞' },
        { v: 'afternoon', label: 'Afternoon', emoji: '🌤️' },
        { v: 'evening',   label: 'Evening',   emoji: '🌙' },
        { v: 'anytime',   label: 'Anytime',   emoji: '🕐' },
    ];
    const slotInfo = (v) => SLOTS.find(s => s.v === v) || SLOTS[SLOTS.length - 1];

    // Weekdays shown Mon-first, but each maps to its JS/PHP day index (0=Sun…6=Sat).
    const WEEKDAYS = [
        { i: 1, label: 'Mon' }, { i: 2, label: 'Tue' }, { i: 3, label: 'Wed' },
        { i: 4, label: 'Thu' }, { i: 5, label: 'Fri' }, { i: 6, label: 'Sat' }, { i: 0, label: 'Sun' },
    ];
    // Build a 7-char mask (index = day-of-week) from a {dayIndex: bool} map.
    function maskFromSet(set) {
        let s = '';
        for (let i = 0; i < 7; i++) s += set[i] ? '1' : '0';
        return s;
    }
    // Human summary of a weekday mask: "Every day", "Weekdays", "Mon, Wed, Fri", etc.
    function dowSummary(mask) {
        mask = /^[01]{7}$/.test(mask) ? mask : '1111111';
        if (mask === '1111111') return 'Every day';
        if (mask === '0111110') return 'Weekdays';
        if (mask === '1000001') return 'Weekends';
        const names = WEEKDAYS.filter(d => mask[d.i] === '1').map(d => d.label);
        return names.length ? names.join(', ') : 'No days set';
    }
    // "08:00" (24h) → "8:00 AM"
    function fmtTime(t) {
        if (!t) return '';
        const p = t.split(':'); let h = +p[0]; const m = p[1];
        const ap = h >= 12 ? 'PM' : 'AM';
        h = h % 12; if (h === 0) h = 12;
        return h + ':' + m + ' ' + ap;
    }

    // Accessible time picker built from native <select>s (Hour / Minutes / AM-PM).
    // Native selects work well with VoiceOver on iOS touch AND JAWS on desktop,
    // unlike <input type="time">. Returns { node, value() } where value() is a
    // 24h "HH:MM" string, or "" when no hour is chosen (time is optional).
    function timeField(value) {
        const pad = (n) => String(n).padStart(2, '0');
        const hourSel = el('select', { 'aria-label': 'Hour' });
        hourSel.appendChild(el('option', { value: '' }, '— none —'));
        for (let h = 1; h <= 12; h++) hourSel.appendChild(el('option', { value: String(h) }, String(h)));

        // Parse an existing "HH:MM" (24h) value.
        let selH = '', selM = 0, selAp = 'AM';
        if (value && /^\d{1,2}:\d{2}$/.test(value)) {
            const h24 = parseInt(value.split(':')[0], 10);
            selM = parseInt(value.split(':')[1], 10);
            selAp = h24 >= 12 ? 'PM' : 'AM';
            let h12 = h24 % 12; if (h12 === 0) h12 = 12;
            selH = String(h12);
        }
        const minutes = [];
        for (let m = 0; m < 60; m += 5) minutes.push(m);
        if (minutes.indexOf(selM) === -1) minutes.push(selM); // keep an off-grid minute
        minutes.sort((a, b) => a - b);
        const minSel = el('select', { 'aria-label': 'Minutes' });
        minutes.forEach(m => minSel.appendChild(el('option', { value: String(m) }, pad(m))));
        const apSel = el('select', { 'aria-label': 'AM or PM' }, [
            el('option', { value: 'AM' }, 'AM'),
            el('option', { value: 'PM' }, 'PM')
        ]);
        if (selH) hourSel.value = selH;
        minSel.value = String(selM);
        apSel.value = selAp;

        const node = el('div', { class: 'time-field', role: 'group', 'aria-label': 'Specific time' }, [
            hourSel,
            el('span', { class: 'time-field__sep', 'aria-hidden': 'true', text: ':' }),
            minSel,
            apSel
        ]);
        return {
            node: node,
            value: function () {
                if (!hourSel.value) return '';
                let h = parseInt(hourSel.value, 10) % 12;
                if (apSel.value === 'PM') h += 12;
                return pad(h) + ':' + pad(parseInt(minSel.value, 10) || 0);
            }
        };
    }

    // -------------------------------------------------- modal
    let lastFocus = null;
    function openModal(title, contentNode, actionsNode) {
        lastFocus = document.activeElement;
        const close = () => closeModal();
        const dialog = el('div', { class: 'modal', role: 'dialog', 'aria-modal': 'true', 'aria-label': title }, [
            el('h2', { text: title }),
            contentNode,
            actionsNode || null
        ]);
        const backdrop = el('div', {
            class: 'modal-backdrop',
            onclick: (e) => { if (e.target === backdrop) close(); }
        }, [dialog]);
        const focusables = () => dialog.querySelectorAll(
            'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])');
        backdrop.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') { close(); return; }
            if (e.key !== 'Tab') return;
            // Keep Tab focus inside the dialog (WCAG 2.4.3 — no escaping to inert page).
            const f = focusables();
            if (!f.length) return;
            const first = f[0], last = f[f.length - 1];
            if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
            else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
        });
        // The rest of the page becomes inert (unfocusable + hidden from assistive
        // tech) so focus can't land behind the open dialog.
        document.querySelectorAll('.skip-link, .app-header, #main, .app-footer')
            .forEach(elm => elm.setAttribute('inert', ''));
        document.body.appendChild(backdrop);
        const focusable = dialog.querySelector('input, select, textarea, button');
        if (focusable) focusable.focus();
        state._modal = backdrop;
        return backdrop;
    }
    function closeModal() {
        if (state._modal) { state._modal.remove(); state._modal = null; }
        document.querySelectorAll('[inert]').forEach(elm => elm.removeAttribute('inert'));
        if (lastFocus && lastFocus.focus) lastFocus.focus();
    }

    // -------------------------------------------------- navigation
    function go(view) { state.view = view; render(); }

    // Switch a sub-view/section without leaving the screen. Defers the re-render
    // so a screen reader finishes activating the button before its DOM is rebuilt
    // (prevents VoiceOver's "no action available"), then lands focus on the newly
    // revealed panel heading so the change is announced.
    function switchPanel() { state._focusSel = '.panel-title'; setTimeout(render, 0); }

    function updateHeader() {
        const box = $('#header-actions');
        box.innerHTML = '';
        if (state.view === 'kid' || state.view === 'kidSummary') {
            box.appendChild(el('button', {
                class: 'header-btn', onclick: kidLogout,
                'aria-label': 'Switch user or log out'
            }, 'Switch user'));
        } else if (state.view === 'parent') {
            box.appendChild(el('button', {
                class: 'header-btn', onclick: () => parentLogout('kid'),
                'aria-label': 'Kid sign-in: sign the parent out and let a kid sign in'
            }, '🧒 Kid sign-in'));
            box.appendChild(el('button', {
                class: 'header-btn', onclick: () => showFamilyCode(state.family ? state.family.code : '', false)
            }, 'Family code'));
            box.appendChild(el('button', {
                class: 'header-btn', onclick: () => openChangePin()
            }, 'Change PIN'));
            box.appendChild(el('button', {
                class: 'header-btn', onclick: () => parentLogout('kid')
            }, 'Log out'));
        }
    }

    function focusMain() {
        const m = $('#main');
        if (m) m.focus();
    }

    async function render() {
        updateHeader();
        const root = $('#view');
        root.innerHTML = '';
        root.appendChild(el('p', { class: 'muted', text: 'Loading…' }));
        let node;
        try {
            if (state.view === 'kid') node = await viewKid();
            else if (state.view === 'kidSummary') node = await viewKidSummary();
            else if (state.view === 'parent') node = await viewParent();
            else node = await viewLogin();
        } catch (e) {
            node = el('div', { class: 'card' }, [
                el('h2', { text: 'Something went wrong' }),
                el('p', { text: e.message || 'Please try again.' }),
                el('button', { class: 'btn', onclick: () => go('login') }, 'Back to start')
            ]);
        }
        root.innerHTML = '';
        root.appendChild(node);
        // Page title reflects the current screen so it's announced + shown in history (WCAG 2.4.2).
        const _h = root.querySelector('h1, h2');
        if (_h) document.title = _h.textContent.trim().replace(/\s+/g, ' ') + ' · Chore Slayer';
        // --- Focus management for screen readers ---
        if (state._focusSel) {
            // Focus a specific element after re-render (e.g. switching a tab lands
            // focus on the revealed panel heading). Make it focusable if needed.
            const target = root.querySelector(state._focusSel);
            state._focusSel = null;
            if (target) {
                if (!target.hasAttribute('tabindex')) target.setAttribute('tabindex', '-1');
                try { target.focus(); } catch (e) { /* ignore */ }
            }
        } else if (state.view !== state._lastView) {
            // Navigated to a new screen — move focus to its heading so the screen
            // reader announces where we are instead of silently swapping content
            // (WCAG 2.4.3 Focus Order / 4.1.3 Status Messages).
            const heading = root.querySelector('h1, h2');
            if (heading) {
                heading.setAttribute('tabindex', '-1');
                try { heading.focus(); } catch (e) { /* ignore */ }
            }
        }
        state._lastView = state.view;
    }

    // ==================================================================
    //  LOGIN
    // ==================================================================
    async function viewLogin() {
        // A device must be paired to a family before kids/parents can sign in.
        if (!state.family) return viewOnboard();
        if (state.loginTab !== 'kid' && state.loginTab !== 'parent') state.loginTab = 'kid';

        const wrap = el('div');
        wrap.appendChild(el('p', { class: 'panel-sub', text: 'Family: ' + (state.family.name || state.family.code) }));
        const tabs = el('div', { class: 'tabs', role: 'group', 'aria-label': 'Who are you?' });
        const kidTab = el('button', {
            class: 'tab', type: 'button', id: 'tab-kid', 'aria-label': "I'm a Kid",
            'aria-current': state.loginTab === 'kid' ? 'true' : 'false',
            onclick: () => { state.loginTab = 'kid'; switchPanel(); }
        }, "🧒 I'm a Kid");
        const parentTab = el('button', {
            class: 'tab', type: 'button', id: 'tab-parent', 'aria-label': 'Parent / Admin',
            'aria-current': state.loginTab === 'parent' ? 'true' : 'false',
            onclick: () => { state.loginTab = 'parent'; switchPanel(); }
        }, '👪 Parent / Admin');
        tabs.appendChild(kidTab);
        tabs.appendChild(parentTab);

        const grid = el('div', { class: 'login-grid' });
        grid.appendChild(state.loginTab === 'kid' ? kidLoginCard() : parentLoginCard());

        wrap.appendChild(tabs);
        wrap.appendChild(grid);
        wrap.appendChild(el('p', { class: 'muted', style: 'margin-top:14px;text-align:center' }, [
            el('button', { class: 'linklike', type: 'button', onclick: switchFamily }, 'Use a different family')
        ]));
        return wrap;
    }

    // -------- Onboarding: create a family, or connect this device ---------
    async function viewOnboard() {
        const wrap = el('div');
        wrap.appendChild(el('div', { class: 'kid-hello' }, [
            el('div', { class: 'kid-avatar', style: 'background:#5b8def', 'aria-hidden': 'true' }, '🗡️'),
            el('div', {}, [
                el('h2', { text: 'Welcome to Chore Slayer' }),
                el('p', { class: 'muted', text: 'Set up your family to get started.' })
            ])
        ]));
        const onConnect = state.loginTab === 'connect';
        const tabs = el('div', { class: 'tabs', role: 'group', 'aria-label': 'Get started' });
        tabs.appendChild(el('button', {
            class: 'tab', type: 'button', id: 'tab-create', 'aria-label': 'Create a family',
            'aria-current': onConnect ? 'false' : 'true',
            onclick: () => { state.loginTab = 'create'; switchPanel(); }
        }, '✨ Create a family'));
        tabs.appendChild(el('button', {
            class: 'tab', type: 'button', id: 'tab-connect', 'aria-label': 'Connect this device',
            'aria-current': onConnect ? 'true' : 'false',
            onclick: () => { state.loginTab = 'connect'; switchPanel(); }
        }, '🔗 Connect this device'));
        const grid = el('div', { class: 'login-grid' });
        grid.appendChild(onConnect ? connectDeviceCard() : createFamilyCard());
        wrap.appendChild(tabs);
        wrap.appendChild(grid);
        return wrap;
    }

    function createFamilyCard() {
        const name = el('input', { type: 'text', id: 'f-name', autocomplete: 'off', 'aria-label': 'Family name (optional)' });
        const pin = el('input', { type: 'password', id: 'p-pin', inputmode: 'numeric', autocomplete: 'new-password', 'aria-label': 'New PIN' });
        const pin2 = el('input', { type: 'password', id: 'p-pin2', inputmode: 'numeric', autocomplete: 'new-password', 'aria-label': 'Confirm PIN' });
        const submit = async (e) => {
            e.preventDefault();
            if (!/^\d{4,8}$/.test(pin.value)) { fieldError(pin, 'PIN must be 4 to 8 digits.'); return; }
            if (pin.value !== pin2.value) { fieldError(pin2, 'The two PINs do not match.'); return; }
            try {
                const r = await api('signup', { method: 'POST', body: { pin: pin.value, name: name.value.trim() } });
                setFamily(r.family); setToken(r.token);
                showFamilyCode(r.family.code, true);
            } catch (err) { toast(err.message, 'error'); }
        };
        return el('form', { class: 'card', onsubmit: submit }, [
            el('h2', { class: 'panel-title', text: 'Create your family' }),
            el('p', { class: 'panel-sub', text: 'Choose a parent PIN (4–8 digits). You’ll get a Family Code to add more devices, sign in on the web, or recover.' }),
            el('div', { class: 'field' }, [el('label', { for: 'f-name', text: 'Family name (optional)' }), name]),
            el('div', { class: 'field' }, [el('label', { for: 'p-pin', text: 'Parent PIN' }), pin]),
            el('div', { class: 'field' }, [el('label', { for: 'p-pin2', text: 'Confirm PIN' }), pin2]),
            el('button', { class: 'btn btn--lg btn--block', type: 'submit' }, 'Create family')
        ]);
    }

    function connectDeviceCard() {
        const code = el('input', { type: 'text', id: 'f-code', autocomplete: 'off', autocapitalize: 'characters', spellcheck: 'false', 'aria-label': 'Family code' });
        const pin = el('input', { type: 'password', id: 'p-pin', inputmode: 'numeric', autocomplete: 'current-password', 'aria-label': 'Parent PIN' });
        const submit = async (e) => {
            e.preventDefault();
            if (!code.value.trim()) { fieldError(code, 'Enter your family code.'); return; }
            try {
                const r = await api('parent_login', { method: 'POST', body: { familyCode: code.value.trim(), pin: pin.value } });
                setFamily(r.family); setToken(r.token);
                toast('Device connected.', 'success');
                go('parent');
            } catch (err) { toast(err.message, 'error'); }
        };
        return el('form', { class: 'card', onsubmit: submit }, [
            el('h2', { class: 'panel-title', text: 'Connect this device' }),
            el('p', { class: 'panel-sub', text: 'Enter your Family Code and parent PIN to link this device to an existing family.' }),
            el('div', { class: 'field' }, [el('label', { for: 'f-code', text: 'Family code' }), code]),
            el('div', { class: 'field' }, [el('label', { for: 'p-pin', text: 'Parent PIN' }), pin]),
            el('button', { class: 'btn btn--lg btn--block', type: 'submit' }, 'Connect')
        ]);
    }

    function showFamilyCode(code, proceed) {
        const content = el('div', {}, [
            el('p', { text: 'Save this Family Code somewhere safe. You’ll need it to add another device, sign in on the web, or recover your account:' }),
            el('div', { style: 'font-size:1.8rem;font-weight:800;letter-spacing:.15em;text-align:center;margin:14px 0;user-select:all' }, code || '—'),
            el('p', { class: 'muted', text: 'From now on, your PIN unlocks the parent area on this device.' })
        ]);
        openModal('Your Family Code', content, el('div', { class: 'modal-actions' }, [
            el('button', { class: 'btn', onclick: () => { closeModal(); if (proceed) go('parent'); } }, 'Got it')
        ]));
    }

    function kidLoginCard() {
        const first = el('input', { type: 'text', id: 'k-first', autocomplete: 'given-name', 'aria-label': 'First name' });
        const last = el('input', { type: 'text', id: 'k-last', autocomplete: 'family-name', 'aria-label': 'Last name' });
        const submit = async (e) => {
            e.preventDefault();
            const f = first.value.trim(), l = last.value.trim();
            if (!f) { fieldError(first, 'Type your first name.'); return; }
            if (!l) { fieldError(last, 'Type your last name.'); return; }
            try {
                if (getToken()) {
                    try { await api('parent_logout', { method: 'POST' }); } catch (e2) { /* ignore */ }
                    setToken(null);
                }
                const r = await api('child_login', { method: 'POST', body: { firstName: f, lastName: l, familyId: state.family.id } });
                state.child = r.child;
                localStorage.setItem(CHILD_KEY, JSON.stringify(r.child));
                go('kid');
            } catch (err) { toast(err.message, 'error'); }
        };
        return el('form', { class: 'card', onsubmit: submit }, [
            el('h2', { class: 'panel-title', text: 'Hi! Let’s log in 👋' }),
            el('p', { class: 'panel-sub', text: 'Type your name the way a parent set it up.' }),
            el('div', { class: 'field' }, [el('label', { for: 'k-first', text: 'First name' }), first]),
            el('div', { class: 'field' }, [el('label', { for: 'k-last', text: 'Last name' }), last]),
            el('button', { class: 'btn btn--lg btn--block', type: 'submit' }, "Let’s go! 🚀")
        ]);
    }

    function parentLoginCard() {
        const pin = el('input', { type: 'password', id: 'p-pin', inputmode: 'numeric', autocomplete: 'current-password', 'aria-label': 'Parent PIN' });
        const submit = async (e) => {
            e.preventDefault();
            try {
                const r = await api('parent_login', { method: 'POST', body: { familyId: state.family.id, pin: pin.value } });
                setToken(r.token);
                if (r.family) setFamily(r.family);
                go('parent');
            } catch (err) { toast(err.message, 'error'); }
        };
        return el('form', { class: 'card', onsubmit: submit }, [
            el('h2', { class: 'panel-title', text: 'Parent sign-in' }),
            el('p', { class: 'panel-sub', text: 'Enter your PIN to manage kids, tasks and reports.' }),
            el('div', { class: 'field' }, [el('label', { for: 'p-pin', text: 'PIN' }), pin]),
            el('button', { class: 'btn btn--lg btn--block', type: 'submit' }, 'Sign in')
        ]);
    }

    function kidLogout() {
        state.child = null; state.kidData = null;
        localStorage.removeItem(CHILD_KEY);
        state.loginTab = 'kid';
        go('login');
    }
    // Ends the parent session on this device (the device stays linked to the
    // family). Lands on the kid sign-in tab by default so the device can be
    // handed straight to a kid.
    async function parentLogout(tab) {
        try { await api('parent_logout', { method: 'POST' }); } catch (e) {}
        setToken(null);
        state.loginTab = tab === 'parent' ? 'parent' : 'kid';
        go('login');
    }
    // Unpair this device from its family entirely (back to onboarding).
    function switchFamily() {
        setFamily(null); setToken(null);
        state.child = null; localStorage.removeItem(CHILD_KEY);
        state.loginTab = 'create';
        go('login');
    }

    // ==================================================================
    //  KID DASHBOARD
    // ==================================================================
    async function viewKid() {
        if (!state.child) { go('login'); return el('div'); }
        const data = await api('day_summary', { params: { childId: state.child.id, day: state.today } });
        state.kidData = data;
        const c = state.child;

        const wrap = el('div');
        wrap.appendChild(el('div', { class: 'kid-hello' }, [
            el('div', { class: 'kid-avatar', style: 'background:' + (c.color || '#5b8def'), 'aria-hidden': 'true' }, c.emoji || '🙂'),
            el('div', {}, [
                el('h2', { text: 'Hi, ' + c.firstName + '!' }),
                el('p', { class: 'muted', text: fmtDate(state.today) })
            ])
        ]));

        wrap.appendChild(progressBlock(data.tasks));

        if (data.tasks.length === 0) {
            wrap.appendChild(el('div', { class: 'empty' },
                'Nothing scheduled for today. Enjoy your day! 🎈'));
        } else {
            // Group by time-of-day slot, in order.
            SLOTS.forEach(slot => {
                const inSlot = data.tasks.filter(t => (t.slot || 'anytime') === slot.v);
                if (!inSlot.length) return;
                wrap.appendChild(el('h3', { class: 'group-title' }, [slot.emoji + ' ' + slot.label]));
                wrap.appendChild(taskGrid(inSlot));
            });
        }

        wrap.appendChild(el('div', { style: 'margin-top:26px; text-align:center;' }, [
            el('button', { class: 'btn btn--good btn--lg', onclick: () => go('kidSummary') },
                'See my day 🎉')
        ]));
        return wrap;
    }

    function progressBlock(tasks) {
        const p = kidProgress(tasks);
        const fill = el('div', { class: 'progress-fill', id: 'kid-progress-fill', style: 'width:' + p.pct + '%' });
        return el('div', { class: 'progress-wrap' }, [
            el('div', { class: 'progress-label' }, [
                el('span', { id: 'kid-progress-text', text: p.full + ' of ' + p.total + ' done' }),
                el('span', { id: 'kid-progress-pct', text: p.pct + '%' })
            ]),
            el('div', {
                class: 'progress-bar', role: 'progressbar',
                'aria-valuemin': '0', 'aria-valuemax': '100', 'aria-valuenow': p.pct,
                'aria-valuetext': p.full + ' of ' + p.total + ' tasks done, ' + p.pct + ' percent',
                'aria-label': 'Daily progress'
            }, [fill])
        ]);
    }

    function taskGrid(tasks) {
        const grid = el('div', { class: 'task-grid' });
        tasks.forEach(t => grid.appendChild(taskTile(t)));
        return grid;
    }

    // A task counts as fully done only when its main box AND every follow-up are checked.
    function stepsDoneCount(task) { return (task.subtasks || []).filter(s => s.done).length; }
    function taskComplete(task) {
        const steps = task.subtasks || [];
        return task.done && stepsDoneCount(task) === steps.length;
    }
    function taskState(task) {
        if (taskComplete(task)) return 'done';
        if (task.done) return 'partial';
        return 'todo';
    }

    function taskTile(task) {
        const wrap = el('div', { class: 'task-wrap' });
        const check = el('span', { class: 'task__check', 'aria-hidden': 'true' });
        const titleEl = el('span', { class: 'task__title', text: task.title });
        const subEl = el('span', { class: 'task__sub' });
        const timeStr = task.time ? fmtTime(task.time) : '';
        const steps = task.subtasks || [];
        const tile = el('button', { class: 'task', type: 'button' }, [
            el('span', { class: 'task__emoji', 'aria-hidden': 'true', text: task.emoji }),
            el('span', { class: 'task__body' }, [titleEl, subEl]),
            check
        ]);

        // Follow-up checks: hidden until the main task is checked.
        let subBox = null;
        const refreshTile = () => {
            const st = taskState(task);
            tile.dataset.state = st;
            tile.setAttribute('aria-pressed', task.done ? 'true' : 'false');
            check.textContent = st === 'done' ? '✓' : (st === 'partial' ? '–' : '');
            let note = (timeStr ? timeStr + ' · ' : '') + (task.type === 'activity' ? 'Activity' : 'Chore');
            if (steps.length) note += ' · ' + stepsDoneCount(task) + '/' + steps.length + ' follow-ups';
            subEl.textContent = note;
            const word = st === 'done' ? 'all done'
                : st === 'partial' ? ('checked, ' + (steps.length - stepsDoneCount(task)) + ' follow-ups left')
                : 'not done yet';
            tile.setAttribute('aria-label', task.title + (timeStr ? ' at ' + timeStr : '') + ' — ' + word);
            if (subBox) subBox.hidden = !task.done;
        };

        if (steps.length) {
            subBox = el('div', { class: 'subtasks' }, [el('p', { class: 'subtasks__title', text: 'Did you also…' })]);
            steps.forEach(st => subBox.appendChild(subCheck(task, st, refreshTile)));
        }

        tile.addEventListener('click', async () => {
            tile.disabled = true;
            try {
                const r = await api('toggle_completion', {
                    method: 'POST',
                    body: { childId: state.child.id, taskId: task.id, day: state.today }
                });
                task.done = r.completed;
                const entry = state.kidData.tasks.find(x => x.id === task.id);
                if (entry) entry.done = task.done;
                refreshTile();
                recomputeKidProgress();
                if (subBox && task.done) announce('Nice! Now finish the follow-up checks for ' + task.title + '.');
                else announce(task.title + (task.done ? ' checked.' : ' unchecked.'));
            } catch (err) {
                toast(err.message, 'error');
            } finally { tile.disabled = false; }
        });

        refreshTile();
        wrap.appendChild(tile);
        if (subBox) wrap.appendChild(subBox);
        return wrap;
    }

    // A single follow-up check; onChange lets the parent tile update its state/progress.
    function subCheck(parentTask, st, onChange) {
        const box = el('span', { class: 'subcheck__box', 'aria-hidden': 'true', text: '✓' });
        const b = el('button', {
            class: 'subcheck', type: 'button',
            'aria-pressed': st.done ? 'true' : 'false',
            'aria-label': st.title + (st.done ? ' — done' : ' — not done yet')
        }, [box, el('span', { text: st.title })]);
        b.addEventListener('click', async () => {
            b.disabled = true;
            try {
                const r = await api('toggle_completion', {
                    method: 'POST',
                    body: { childId: state.child.id, taskId: st.id, day: state.today }
                });
                st.done = r.completed;
                b.setAttribute('aria-pressed', st.done ? 'true' : 'false');
                b.setAttribute('aria-label', st.title + (st.done ? ' — done' : ' — not done yet'));
                if (onChange) onChange();
                recomputeKidProgress();
                announce(st.title + (st.done ? ' checked.' : ' unchecked.'));
            } catch (err) {
                toast(err.message, 'error');
            } finally { b.disabled = false; }
        });
        return b;
    }

    // Granular progress: every box (main task + each follow-up) is one unit, so the
    // bar advances as follow-ups are ticked. "X of Y done" counts fully-done tasks.
    function kidProgress(tasks) {
        let totalUnits = 0, doneUnits = 0, full = 0;
        tasks.forEach(t => {
            const steps = t.subtasks || [];
            totalUnits += 1 + steps.length;
            doneUnits += (t.done ? 1 : 0) + steps.filter(s => s.done).length;
            if (taskComplete(t)) full++;
        });
        return { pct: totalUnits ? Math.round(doneUnits * 100 / totalUnits) : 0, full: full, total: tasks.length };
    }

    function recomputeKidProgress() {
        const p = kidProgress(state.kidData.tasks);
        const fill = $('#kid-progress-fill');
        if (fill) {
            fill.style.width = p.pct + '%';
            fill.parentElement.setAttribute('aria-valuenow', p.pct);
            fill.parentElement.setAttribute('aria-valuetext', p.full + ' of ' + p.total + ' tasks done, ' + p.pct + ' percent');
            $('#kid-progress-text').textContent = p.full + ' of ' + p.total + ' done';
            $('#kid-progress-pct').textContent = p.pct + '%';
        }
    }

    // ==================================================================
    //  KID "MY DAY" SUMMARY
    // ==================================================================
    async function viewKidSummary() {
        if (!state.child) { go('login'); return el('div'); }
        const data = await api('day_summary', { params: { childId: state.child.id, day: state.today } });
        const c = data.counts;
        const pct = c.percent;

        const wrap = el('div', { class: 'card' });
        wrap.appendChild(el('div', { class: 'summary-hero' }, [
            el('h2', { class: 'panel-title', text: state.child.firstName + "'s Day" }),
            el('p', { class: 'muted', text: fmtDate(state.today) }),
            ring(pct),
            el('p', { class: 'cheer', text: cheerFor(pct) }),
            el('div', { class: 'stars', 'aria-label': starCount(pct) + ' out of 5 stars',
                text: '★★★★★☆☆☆☆☆'.slice(5 - starCount(pct), 10 - starCount(pct)) })
        ]));

        // Accessible text summary
        wrap.appendChild(el('p', { class: 'sr-only' },
            'You finished ' + c.done + ' out of ' + c.total + ' tasks, ' + pct + ' percent.'));

        wrap.appendChild(el('div', { class: 'stat-grid' }, [
            stat(c.done, 'Finished'),
            stat(c.total - c.done, 'Still to do'),
            stat(c.chores.done + '/' + c.chores.total, 'Chores'),
            stat(c.activities.done + '/' + c.activities.total, 'Activities'),
        ]));

        const list = el('ul', { class: 'summary-list' });
        data.tasks.forEach(t => {
            const timeStr = t.time ? fmtTime(t.time) : '';
            const complete = (t.complete !== undefined) ? t.complete : taskComplete(t);
            const partial = !complete && t.done;
            const cls = complete ? 'done' : (partial ? 'partial' : 'todo');
            const icon = complete ? '✅' : (partial ? '🟡' : '⬜');
            const badge = complete ? 'Done' : (partial ? 'Partly' : 'Not yet');
            list.appendChild(el('li', { class: 'summary-item ' + cls }, [
                el('span', { 'aria-hidden': 'true', text: icon }),
                el('span', { class: 'grow' }, [
                    t.emoji + ' ' + t.title,
                    timeStr ? el('span', { class: 'meta', text: '  ·  ' + slotInfo(t.slot).emoji + ' ' + timeStr }) : null
                ]),
                el('span', { class: 'badge', text: badge })
            ]));
            // Follow-up checks, indented beneath the task.
            (t.subtasks || []).forEach(s => {
                list.appendChild(el('li', { class: 'summary-subitem ' + (s.done ? 'done' : 'todo') }, [
                    el('span', { 'aria-hidden': 'true', text: s.done ? '✅' : '⬜' }),
                    el('span', { class: 'grow', text: s.title }),
                    el('span', { class: 'badge', text: s.done ? 'Done' : 'Not yet' })
                ]));
            });
        });
        if (data.tasks.length) wrap.appendChild(list);
        else wrap.appendChild(el('p', { class: 'empty' }, 'Nothing on the list today.'));

        wrap.appendChild(el('div', { class: 'modal__actions no-print', style: 'margin-top:22px' }, [
            el('button', { class: 'btn btn--ghost', onclick: () => go('kid') }, '← Back to my chores'),
            el('button', { class: 'btn', onclick: () => window.print() }, '🖨️ Print my day')
        ]));
        return wrap;
    }

    function ring(pct) {
        const r = 52, circ = 2 * Math.PI * r;
        const off = circ * (1 - pct / 100);
        const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('viewBox', '0 0 130 130');
        svg.setAttribute('width', '160'); svg.setAttribute('height', '160');
        svg.setAttribute('class', 'ring');
        svg.setAttribute('role', 'img');
        svg.setAttribute('aria-label', pct + ' percent of tasks done');
        svg.innerHTML =
            '<circle class="ring__bg" cx="65" cy="65" r="' + r + '" stroke-width="13"></circle>' +
            '<circle class="ring__fg" cx="65" cy="65" r="' + r + '" stroke-width="13" ' +
            'stroke-dasharray="' + circ.toFixed(1) + '" stroke-dashoffset="' + off.toFixed(1) + '"></circle>' +
            '<text class="ring__text" x="65" y="73">' + pct + '%</text>';
        return el('div', { class: 'ring-wrap' }, [svg]);
    }
    function cheerFor(pct) {
        if (pct >= 100) return 'Amazing! You finished everything! 🏆';
        if (pct >= 75) return 'Awesome work — almost there! 🌟';
        if (pct >= 50) return 'Great job, halfway and beyond! 💪';
        if (pct >= 25) return 'Good start — keep going! 🙌';
        if (pct > 0) return "You've begun — you can do it! 👍";
        return "Let's get started when you're ready! 🌱";
    }
    function starCount(pct) { return Math.max(0, Math.min(5, Math.round(pct / 20))); }
    function stat(num, label) {
        return el('div', { class: 'stat' }, [
            el('div', { class: 'stat__num', text: String(num) }),
            el('div', { class: 'stat__label', text: label })
        ]);
    }

    // ==================================================================
    //  PARENT AREA
    // ==================================================================
    async function viewParent() {
        // refresh children cache
        const cr = await api('children');
        state.children = cr.children;
        if (!state.selectedChildId && state.children.length) {
            state.selectedChildId = state.children[0].id;
        }

        const wrap = el('div');
        const tabs = el('div', { class: 'tabs', role: 'group', 'aria-label': 'Parent sections' });
        const defs = [
            ['children', '👧 Children', 'Children'],
            ['tasks', '📋 Chores & Activities', 'Chores & Activities'],
            ['report', '📝 Daily Report', 'Daily Report'],
            ['bhis', '📊 BHIS Report', 'BHIS Report'],
        ];
        defs.forEach(([key, label, alabel]) => {
            tabs.appendChild(el('button', {
                class: 'tab', type: 'button', id: 'ptab-' + key, 'aria-label': alabel,
                'aria-current': state.parentTab === key ? 'true' : 'false',
                onclick: () => { state.parentTab = key; switchPanel(); }
            }, label));
        });
        wrap.appendChild(tabs);

        let panel;
        if (state.parentTab === 'children') panel = parentChildren();
        else if (state.parentTab === 'tasks') panel = await parentTasks();
        else if (state.parentTab === 'report') panel = await parentReport();
        else panel = await parentBhis();
        wrap.appendChild(panel);
        return wrap;
    }

    function childSelect(onChange, id) {
        const sel = el('select', { id: id || 'child-select', 'aria-label': 'Choose a child' });
        if (!state.children.length) {
            sel.appendChild(el('option', { value: '' }, 'No children yet'));
            sel.disabled = true;
        }
        state.children.forEach(c => {
            const o = el('option', { value: c.id }, c.firstName + ' ' + c.lastName);
            if (c.id === state.selectedChildId) o.selected = true;
            sel.appendChild(o);
        });
        sel.addEventListener('change', () => {
            state.selectedChildId = sel.value;
            if (onChange) onChange(sel.value);
        });
        return sel;
    }

    // ---------- Children tab ----------
    function parentChildren() {
        const card = el('div', { class: 'card' });
        card.appendChild(el('div', { style: 'display:flex; align-items:center; gap:12px; flex-wrap:wrap' }, [
            el('div', { class: 'grow', style: 'flex:1' }, [
                el('h2', { class: 'panel-title', text: 'Children' }),
                el('p', { class: 'panel-sub', text: 'Add the kids who will use the app, or remove ones who no longer need it.' })
            ]),
            el('button', { class: 'btn', onclick: () => openChildForm(null) }, '➕ Add child')
        ]));

        if (!state.children.length) {
            card.appendChild(el('div', { class: 'empty' }, 'No children yet. Click “Add child” to begin.'));
            return card;
        }
        const list = el('ul', { class: 'list' });
        state.children.forEach(c => {
            list.appendChild(el('li', { class: 'list-item' }, [
                el('span', { class: 'pill-emoji', style: 'background:' + c.color, 'aria-hidden': 'true', text: c.emoji }),
                el('div', { class: 'grow' }, [
                    el('div', { class: 'name', text: c.firstName + ' ' + c.lastName }),
                    el('div', { class: 'meta', text: 'Logs in with this exact name' })
                ]),
                el('div', { class: 'actions' }, [
                    el('button', { class: 'btn btn--small btn--ghost', onclick: () => openChildForm(c) }, 'Edit'),
                    el('button', { class: 'btn btn--small btn--danger', onclick: () => confirmRemoveChild(c) }, 'Remove')
                ])
            ]));
        });
        card.appendChild(list);
        return card;
    }

    function openChildForm(child) {
        const isEdit = !!child;
        const first = el('input', { type: 'text', value: child ? child.firstName : '', 'aria-label': 'First name' });
        const last = el('input', { type: 'text', value: child ? child.lastName : '', 'aria-label': 'Last name' });
        let chosenEmoji = child ? child.emoji : '🙂';
        let chosenColor = child ? child.color : COLORS[0];

        const emojiRow = el('div', { class: 'choice-row' });
        EMOJIS.forEach(e => {
            const b = el('button', { type: 'button', class: 'choice', 'aria-pressed': e === chosenEmoji ? 'true' : 'false', text: e });
            b.addEventListener('click', () => {
                chosenEmoji = e;
                emojiRow.querySelectorAll('.choice').forEach(x => x.setAttribute('aria-pressed', 'false'));
                b.setAttribute('aria-pressed', 'true');
            });
            emojiRow.appendChild(b);
        });
        const colorRow = el('div', { class: 'choice-row' });
        COLORS.forEach(col => {
            const b = el('button', {
                type: 'button', class: 'choice', 'aria-pressed': col === chosenColor ? 'true' : 'false',
                'aria-label': 'color ' + col,
                style: 'background:' + col + ';width:46px'
            });
            b.addEventListener('click', () => {
                chosenColor = col;
                colorRow.querySelectorAll('.choice').forEach(x => x.setAttribute('aria-pressed', 'false'));
                b.setAttribute('aria-pressed', 'true');
            });
            colorRow.appendChild(b);
        });

        const content = el('div', {}, [
            el('div', { class: 'row' }, [
                el('div', { class: 'field' }, [el('label', { text: 'First name' }), first]),
                el('div', { class: 'field' }, [el('label', { text: 'Last name' }), last]),
            ]),
            el('div', { class: 'field' }, [el('label', { text: 'Pick an icon' }), emojiRow]),
            el('div', { class: 'field' }, [el('label', { text: 'Pick a color' }), colorRow]),
            isEdit ? el('p', { class: 'help' }, 'Tip: “Remove” keeps the child’s report history. Use it for kids who no longer need the app.') : null
        ]);
        const save = async () => {
            const f = first.value.trim(), l = last.value.trim();
            if (!f || !l) { toast('First and last name are required.', 'error'); return; }
            try {
                if (isEdit) {
                    await api('update_child', { method: 'POST', body: { id: child.id, firstName: f, lastName: l, emoji: chosenEmoji, color: chosenColor } });
                    toast('Child updated.', 'success');
                } else {
                    await api('add_child', { method: 'POST', body: { firstName: f, lastName: l, emoji: chosenEmoji, color: chosenColor } });
                    toast('Child added.', 'success');
                }
                closeModal(); render();
            } catch (err) { toast(err.message, 'error'); }
        };
        const actions = el('div', { class: 'modal__actions' }, [
            isEdit ? el('button', { class: 'btn btn--small btn--danger', onclick: () => { closeModal(); confirmDeleteForever(child); } }, 'Delete forever') : null,
            el('button', { class: 'btn btn--ghost', onclick: closeModal }, 'Cancel'),
            el('button', { class: 'btn', onclick: save }, isEdit ? 'Save' : 'Add child')
        ]);
        openModal(isEdit ? 'Edit child' : 'Add a child', content, actions);
    }

    function confirmRemoveChild(child) {
        const content = el('p', {}, 'Remove ' + child.firstName + ' ' + child.lastName +
            ' from the active list? Their past reports are kept for your records, and you can add them back any time.');
        const actions = el('div', { class: 'modal__actions' }, [
            el('button', { class: 'btn btn--ghost', onclick: closeModal }, 'Cancel'),
            el('button', { class: 'btn btn--danger', onclick: async () => {
                try { await api('remove_child', { method: 'POST', body: { id: child.id } });
                    if (state.selectedChildId === child.id) state.selectedChildId = '';
                    toast('Child removed.', 'success'); closeModal(); render();
                } catch (err) { toast(err.message, 'error'); }
            } }, 'Remove')
        ]);
        openModal('Remove child', content, actions);
    }
    function confirmDeleteForever(child) {
        const content = el('div', {}, [
            el('p', {}, 'Permanently delete ' + child.firstName + ' ' + child.lastName +
                ' and ALL of their chores, check-offs and reports? This cannot be undone.'),
            el('p', { class: 'help' }, 'If you only want to stop them using the app, use “Remove” instead — that keeps records.')
        ]);
        const actions = el('div', { class: 'modal__actions' }, [
            el('button', { class: 'btn btn--ghost', onclick: closeModal }, 'Cancel'),
            el('button', { class: 'btn btn--danger', onclick: async () => {
                try { await api('delete_child_forever', { method: 'POST', body: { id: child.id } });
                    if (state.selectedChildId === child.id) state.selectedChildId = '';
                    toast('Permanently deleted.', 'success'); closeModal(); render();
                } catch (err) { toast(err.message, 'error'); }
            } }, 'Delete forever')
        ]);
        openModal('Delete forever', content, actions);
    }

    // ---------- Chores & Activities tab ----------
    async function parentTasks() {
        const card = el('div', { class: 'card' });
        card.appendChild(el('div', { style: 'display:flex; align-items:center; gap:12px; flex-wrap:wrap' }, [
            el('div', { class: 'grow', style: 'flex:1' }, [
                el('h2', { class: 'panel-title', text: 'Chores & Activities' }),
                el('p', { class: 'panel-sub', text: 'Set what shows up on a child’s board, which days it repeats, and roughly when.' })
            ]),
            state.children.length ? el('button', { class: 'btn', onclick: () => openTaskForm(null) }, '➕ Add') : null
        ]));

        if (!state.children.length) {
            card.appendChild(el('div', { class: 'empty' }, 'Add a child first on the “Children” tab.'));
            return card;
        }
        card.appendChild(el('div', { class: 'field' }, [
            el('label', { text: 'Child' }),
            childSelect(() => render())
        ]));

        const childId = state.selectedChildId;
        const data = await api('tasks', { params: { childId: childId } });

        if (!data.tasks.length) {
            card.appendChild(el('div', { class: 'empty' }, 'No chores or activities yet. Click “Add” to create one.'));
            return card;
        }
        const list = el('ul', { class: 'list' });
        data.tasks.forEach(t => {
            const si = slotInfo(t.timeSlot);
            const stepN = (t.subtasks || []).length;
            const when = si.emoji + ' ' + si.label + (t.timeOfDay ? ' · ' + fmtTime(t.timeOfDay) : '')
                + (stepN ? '  ·  ☑️ ' + stepN + ' follow-up' + (stepN > 1 ? 's' : '') : '');
            list.appendChild(el('li', { class: 'list-item' }, [
                el('span', { class: 'pill-emoji', style: 'background:' + (t.type === 'activity' ? '#f2b705' : '#5b8def'), 'aria-hidden': 'true', text: t.emoji }),
                el('div', { class: 'grow' }, [
                    el('div', {}, [
                        el('span', { class: 'name', text: t.title }), ' ',
                        el('span', { class: 'tag tag--' + t.type, text: t.type === 'activity' ? 'Activity' : 'Chore' })
                    ]),
                    el('div', { class: 'meta', text: dowSummary(t.daysOfWeek) + '  ·  ' + when })
                ]),
                el('div', { class: 'actions' }, [
                    el('button', { class: 'btn btn--small btn--ghost', onclick: () => openTaskForm(t) }, 'Edit'),
                    el('button', { class: 'btn btn--small btn--danger', onclick: () => confirmRemoveTask(t) }, 'Remove')
                ])
            ]));
        });
        card.appendChild(list);
        return card;
    }

    function confirmRemoveTask(task) {
        const content = el('p', {}, 'Remove “' + task.title + '” from this child’s board? Past check-offs stay in your reports.');
        const actions = el('div', { class: 'modal__actions' }, [
            el('button', { class: 'btn btn--ghost', onclick: closeModal }, 'Cancel'),
            el('button', { class: 'btn btn--danger', onclick: async () => {
                try { await api('remove_task', { method: 'POST', body: { id: task.id } });
                    toast('Removed.', 'success'); closeModal(); render();
                } catch (err) { toast(err.message, 'error'); }
            } }, 'Remove')
        ]);
        openModal('Remove task', content, actions);
    }

    function openTaskForm(task) {
        const isEdit = !!task;
        const title = el('input', { type: 'text', value: task ? task.title : '', 'aria-label': 'Task title', placeholder: 'e.g. Make the bed' });
        const type = el('select', { 'aria-label': 'Type' }, [
            el('option', { value: 'chore' }, 'Chore'),
            el('option', { value: 'activity' }, 'Activity')
        ]);
        if (task) type.value = task.type;
        const slot = el('select', { 'aria-label': 'Time of day' },
            SLOTS.map(s => el('option', { value: s.v }, s.emoji + ' ' + s.label)));
        slot.value = task ? task.timeSlot : 'anytime';
        const time = timeField(task && task.timeOfDay ? task.timeOfDay : '');

        // Weekday checkboxes
        const mask = task && /^[01]{7}$/.test(task.daysOfWeek) ? task.daysOfWeek : '1111111';
        const set = {}; for (let i = 0; i < 7; i++) set[i] = mask[i] === '1';
        const dayRow = el('div', { class: 'choice-row', role: 'group', 'aria-label': 'Days of the week' });
        WEEKDAYS.forEach(d => {
            const b = el('button', { type: 'button', class: 'choice', 'aria-pressed': set[d.i] ? 'true' : 'false', text: d.label });
            b.addEventListener('click', () => { set[d.i] = !set[d.i]; b.setAttribute('aria-pressed', set[d.i] ? 'true' : 'false'); });
            dayRow.appendChild(b);
        });
        const preset = (m) => { for (let i = 0; i < 7; i++) set[i] = m[i] === '1'; [...dayRow.children].forEach((b, idx) => { const di = WEEKDAYS[idx].i; b.setAttribute('aria-pressed', set[di] ? 'true' : 'false'); }); };
        const presets = el('div', { class: 'choice-row', style: 'margin-top:8px' }, [
            el('button', { type: 'button', class: 'btn btn--small btn--ghost', onclick: () => preset('1111111') }, 'Every day'),
            el('button', { type: 'button', class: 'btn btn--small btn--ghost', onclick: () => preset('0111110') }, 'Weekdays'),
            el('button', { type: 'button', class: 'btn btn--small btn--ghost', onclick: () => preset('1000001') }, 'Weekends'),
        ]);

        // Follow-up checks editor: rows that reconcile on save.
        const subList = el('div', { class: 'sub-edit-list' });
        const subRows = [];
        function addSubRow(existing) {
            const input = el('input', { type: 'text', value: existing ? existing.title : '', 'aria-label': 'Follow-up check', placeholder: 'e.g. Did you wipe the chairs?' });
            const rec = { id: existing ? existing.id : null, orig: existing ? existing.title : '', input: input, removed: false };
            const rowEl = el('div', { class: 'sub-edit-row' }, [
                input,
                el('button', { type: 'button', class: 'btn btn--small btn--danger', 'aria-label': 'Remove this check', onclick: () => { rec.removed = true; rowEl.remove(); } }, '✕')
            ]);
            subRows.push(rec);
            subList.appendChild(rowEl);
            input.focus();
        }
        (task && task.subtasks ? task.subtasks : []).forEach(addSubRow);

        const content = el('div', {}, [
            el('div', { class: 'field' }, [el('label', { text: 'Name' }), title]),
            el('div', { class: 'row' }, [
                el('div', { class: 'field' }, [el('label', { text: 'Type' }), type]),
                el('div', { class: 'field' }, [el('label', { text: 'Time of day' }), slot]),
                el('div', { class: 'field' }, [el('label', { text: 'Specific time' }), time.node, el('div', { class: 'help', text: 'Optional' })])
            ]),
            el('div', { class: 'field' }, [el('label', { text: 'Repeats on' }), dayRow, presets]),
            el('div', { class: 'field' }, [
                el('label', { text: 'Follow-up checks (optional)' }),
                el('p', { class: 'help', text: 'These appear after the kid checks the main task — e.g. for “Wipe table”: “Did you wipe it twice?”, “Did you wipe the chairs?”' }),
                subList,
                el('button', { type: 'button', class: 'btn btn--small btn--ghost', onclick: () => addSubRow(null) }, '➕ Add a check')
            ])
        ]);
        const save = async () => {
            const t = title.value.trim();
            if (!t) { toast('Type a name for the task.', 'error'); return; }
            const daysOfWeek = maskFromSet(set);
            if (!/[1]/.test(daysOfWeek)) { toast('Pick at least one day.', 'error'); return; }
            const body = { title: t, type: type.value, timeSlot: slot.value, timeOfDay: time.value(), daysOfWeek: daysOfWeek };
            try {
                let mainId;
                if (isEdit) {
                    body.id = task.id;
                    await api('update_task', { method: 'POST', body: body });
                    mainId = task.id;
                } else {
                    body.childId = state.selectedChildId;
                    const res = await api('add_task', { method: 'POST', body: body });
                    mainId = res.id;
                }
                // Reconcile follow-up checks.
                const ops = [];
                subRows.forEach(r => {
                    const st = r.input.value.trim();
                    if (r.removed || st === '') {
                        if (r.id) ops.push(api('remove_task', { method: 'POST', body: { id: r.id } }));
                    } else if (r.id) {
                        if (st !== r.orig) ops.push(api('update_subtask', { method: 'POST', body: { id: r.id, title: st } }));
                    } else {
                        ops.push(api('add_subtask', { method: 'POST', body: { parentId: mainId, title: st } }));
                    }
                });
                await Promise.all(ops);
                toast(isEdit ? 'Saved.' : 'Added.', 'success');
                closeModal(); render();
            } catch (err) { toast(err.message, 'error'); }
        };
        const actions = el('div', { class: 'modal__actions' }, [
            el('button', { class: 'btn btn--ghost', onclick: closeModal }, 'Cancel'),
            el('button', { class: 'btn', onclick: save }, isEdit ? 'Save' : 'Add')
        ]);
        openModal(isEdit ? 'Edit task' : 'Add chore or activity', content, actions);
    }

    // ---------- Daily Report tab ----------
    async function parentReport() {
        const card = el('div', { class: 'card' });
        card.appendChild(el('h2', { class: 'panel-title', text: 'Daily Report' }));
        card.appendChild(el('p', { class: 'panel-sub', text: 'Write a short note about each child’s day. These feed the BHIS report.' }));

        if (!state.children.length) {
            card.appendChild(el('div', { class: 'empty' }, 'Add a child first on the “Children” tab.'));
            return card;
        }

        const dateInput = el('input', { type: 'date', value: state._reportDay || state.today, max: state.today, 'aria-label': 'Report date' });
        card.appendChild(el('div', { class: 'row' }, [
            el('div', { class: 'field' }, [el('label', { text: 'Child' }), childSelect(() => render())]),
            el('div', { class: 'field' }, [el('label', { text: 'Date' }), dateInput])
        ]));
        dateInput.addEventListener('change', () => { state._reportDay = dateInput.value; render(); });

        const childId = state.selectedChildId;
        const day = state._reportDay || state.today;
        const data = await api('report', { params: { childId: childId, day: day } });
        const r = data.report || {};
        const sum = data.summary;

        // Day snapshot
        card.appendChild(el('div', { class: 'stat-grid' }, [
            stat(sum.counts.done + '/' + sum.counts.total, 'Tasks done'),
            stat(sum.counts.percent + '%', 'Completion'),
            stat(sum.counts.chores.done + '/' + sum.counts.chores.total, 'Chores'),
            stat(sum.counts.activities.done + '/' + sum.counts.activities.total, 'Activities'),
        ]));

        // Per-day schedule (adjust a single day's time/slot or skip a task)
        card.appendChild(await scheduleEditor(childId, day));

        // Mood picker
        let chosenMood = r.mood || '';
        const moodRow = el('div', { class: 'choice-row' });
        MOODS.forEach(m => {
            const b = el('button', { type: 'button', class: 'choice', 'aria-pressed': m.v === chosenMood ? 'true' : 'false', text: m.label });
            b.addEventListener('click', () => {
                chosenMood = (chosenMood === m.v) ? '' : m.v;
                moodRow.querySelectorAll('.choice').forEach(x => x.setAttribute('aria-pressed', 'false'));
                if (chosenMood) b.setAttribute('aria-pressed', 'true');
            });
            moodRow.appendChild(b);
        });

        // Behavior rating 1-5
        let chosenRating = r.behaviorRating || 0;
        const ratingRow = el('div', { class: 'choice-row' });
        for (let i = 1; i <= 5; i++) {
            const b = el('button', { type: 'button', class: 'choice', 'aria-pressed': i === chosenRating ? 'true' : 'false', 'aria-label': i + ' out of 5', text: String(i) });
            b.addEventListener('click', () => {
                chosenRating = (chosenRating === i) ? 0 : i;
                ratingRow.querySelectorAll('.choice').forEach(x => x.setAttribute('aria-pressed', 'false'));
                if (chosenRating) b.setAttribute('aria-pressed', 'true');
            });
            ratingRow.appendChild(b);
        }

        const highlights = el('textarea', { 'aria-label': 'Highlights / wins' }, r.highlights || '');
        const challenges = el('textarea', { 'aria-label': 'Challenges' }, r.challenges || '');
        const notes = el('textarea', { 'aria-label': 'General notes' }, r.notes || '');
        const goals = el('textarea', { 'aria-label': 'Goals for tomorrow' }, r.goals || '');

        const save = async () => {
            try {
                await api('save_report', { method: 'POST', body: {
                    childId: childId, day: day, mood: chosenMood, behaviorRating: chosenRating || '',
                    highlights: highlights.value, challenges: challenges.value,
                    notes: notes.value, goals: goals.value
                } });
                toast('Report saved.', 'success');
            } catch (err) { toast(err.message, 'error'); }
        };

        card.appendChild(el('div', { class: 'field' }, [el('label', { text: 'Mood' }), moodRow]));
        card.appendChild(el('div', { class: 'field' }, [el('label', { text: 'Behavior rating (1 = tough day, 5 = great day)' }), ratingRow]));
        card.appendChild(el('div', { class: 'field' }, [el('label', { text: 'Highlights / wins' }), highlights]));
        card.appendChild(el('div', { class: 'field' }, [el('label', { text: 'Challenges' }), challenges]));
        card.appendChild(el('div', { class: 'field' }, [el('label', { text: 'General notes' }), notes]));
        card.appendChild(el('div', { class: 'field' }, [el('label', { text: 'Goals for tomorrow' }), goals]));
        card.appendChild(el('div', { class: 'modal__actions' }, [
            el('button', { class: 'btn btn--good', onclick: save }, '💾 Save report')
        ]));
        if (r.updatedAt) card.appendChild(el('p', { class: 'help', text: 'Last saved: ' + r.updatedAt }));
        return card;
    }

    // Schedule editor: the day's scheduled tasks with per-day time/skip controls.
    async function scheduleEditor(childId, day) {
        const wrap = el('div', { class: 'field' });
        wrap.appendChild(el('label', { text: 'Today’s schedule' }));
        wrap.appendChild(el('p', { class: 'help', text: 'Adjust just this day’s time, or skip a task — the recurring schedule is unchanged.' }));
        let data;
        try { data = await api('day_schedule', { params: { childId: childId, day: day } }); }
        catch (err) { wrap.appendChild(el('div', { class: 'empty' }, 'Could not load the schedule.')); return wrap; }

        if (!data.tasks.length) {
            wrap.appendChild(el('div', { class: 'empty' }, 'Nothing scheduled on this day.'));
            return wrap;
        }
        const list = el('ul', { class: 'list' });
        data.tasks.forEach(t => {
            const si = slotInfo(t.effectiveSlot);
            const when = si.emoji + ' ' + si.label + (t.effectiveTime ? ' · ' + fmtTime(t.effectiveTime) : '');
            const changed = t.overrideSlot || t.overrideTime;
            list.appendChild(el('li', { class: 'list-item' + (t.skipped ? ' is-skipped' : '') }, [
                el('span', { class: 'pill-emoji', style: 'background:' + (t.type === 'activity' ? '#f2b705' : '#5b8def'), 'aria-hidden': 'true', text: t.emoji }),
                el('div', { class: 'grow' }, [
                    el('div', {}, [
                        el('span', { class: 'name', text: t.title }),
                        t.skipped ? el('span', { class: 'tag tag--inactive', text: 'Skipped today' }) : null,
                        (!t.skipped && changed) ? el('span', { class: 'tag tag--activity', text: 'Adjusted' }) : null
                    ]),
                    el('div', { class: 'meta', text: t.skipped ? 'Not shown on the board today' : when })
                ]),
                el('div', { class: 'actions' }, [
                    el('button', { class: 'btn btn--small btn--ghost', onclick: () => openOverrideForm(t, day) }, 'Adjust'),
                    el('button', {
                        class: 'btn btn--small ' + (t.skipped ? 'btn--good' : 'btn--ghost'),
                        onclick: () => toggleSkip(t, day)
                    }, t.skipped ? 'Restore' : 'Skip today')
                ])
            ]));
        });
        wrap.appendChild(list);
        return wrap;
    }

    async function toggleSkip(task, day) {
        try {
            await api('set_override', { method: 'POST', body: {
                taskId: task.id, day: day,
                timeSlot: task.overrideSlot || '', timeOfDay: task.overrideTime || '',
                skipped: task.skipped ? '0' : '1'
            } });
            toast(task.skipped ? 'Restored for this day.' : 'Skipped for this day.', 'success');
            render();
        } catch (err) { toast(err.message, 'error'); }
    }

    function openOverrideForm(task, day) {
        const slot = el('select', { 'aria-label': 'Time of day for this day' },
            [el('option', { value: '' }, '— Use usual (' + slotInfo(task.defaultSlot).label + ') —')]
                .concat(SLOTS.map(s => el('option', { value: s.v }, s.emoji + ' ' + s.label))));
        slot.value = task.overrideSlot || '';
        const time = timeField(task.overrideTime || '');
        const content = el('div', {}, [
            el('p', { class: 'help', text: 'Changes apply to ' + fmtDate(day) + ' only. Leave blank to use the usual schedule.' }),
            el('div', { class: 'row' }, [
                el('div', { class: 'field' }, [el('label', { text: 'Time of day' }), slot]),
                el('div', { class: 'field' }, [el('label', { text: 'Specific time' }), time.node])
            ])
        ]);
        const actions = el('div', { class: 'modal__actions' }, [
            el('button', { class: 'btn btn--ghost', onclick: closeModal }, 'Cancel'),
            (task.overrideSlot || task.overrideTime) ? el('button', { class: 'btn btn--ghost', onclick: async () => {
                try { await api('set_override', { method: 'POST', body: { taskId: task.id, day: day, timeSlot: '', timeOfDay: '', skipped: task.skipped ? '1' : '0' } });
                    toast('Reset to usual.', 'success'); closeModal(); render();
                } catch (err) { toast(err.message, 'error'); }
            } }, 'Reset to usual') : null,
            el('button', { class: 'btn', onclick: async () => {
                try { await api('set_override', { method: 'POST', body: {
                    taskId: task.id, day: day, timeSlot: slot.value, timeOfDay: time.value(),
                    skipped: task.skipped ? '1' : '0'
                } });
                    toast('Saved for this day.', 'success'); closeModal(); render();
                } catch (err) { toast(err.message, 'error'); }
            } }, 'Save for this day')
        ]);
        openModal('Adjust “' + task.title + '” for one day', content, actions);
    }

    // ---------- BHIS Report tab ----------
    async function parentBhis() {
        const card = el('div', { class: 'card no-print' });
        card.appendChild(el('h2', { class: 'panel-title', text: 'BHIS Report' }));
        card.appendChild(el('p', { class: 'panel-sub', text: 'Build a summary of a child’s progress over a date range to share with a BHIS worker.' }));

        if (!state.children.length) {
            card.appendChild(el('div', { class: 'empty' }, 'Add a child first on the “Children” tab.'));
            return el('div', {}, [card]);
        }

        const fromInput = el('input', { type: 'date', value: state._from || daysAgoStr(6), max: state.today, 'aria-label': 'From date' });
        const toInput = el('input', { type: 'date', value: state._to || state.today, max: state.today, 'aria-label': 'To date' });

        card.appendChild(el('div', { class: 'row' }, [
            el('div', { class: 'field' }, [el('label', { text: 'Child' }), childSelect()]),
            el('div', { class: 'field' }, [el('label', { text: 'From' }), fromInput]),
            el('div', { class: 'field' }, [el('label', { text: 'To' }), toInput]),
        ]));
        card.appendChild(el('div', { class: 'modal__actions' }, [
            el('button', { class: 'btn', onclick: async () => {
                state._from = fromInput.value; state._to = toInput.value;
                try {
                    state.bhis = await api('bhis', { params: { childId: state.selectedChildId, from: state._from, to: state._to } });
                    render();
                } catch (err) { toast(err.message, 'error'); }
            } }, '📊 Generate report')
        ]));

        const out = el('div');
        if (state.bhis) out.appendChild(bhisSheet(state.bhis));
        return el('div', {}, [card, out]);
    }

    function bhisSheet(rep) {
        const sheet = el('div', { class: 'report-sheet', id: 'bhis-sheet' });
        const childName = rep.child ? rep.child.name : 'Child';
        sheet.appendChild(el('h2', { text: 'Progress Report — ' + childName }));
        sheet.appendChild(el('p', { class: 'muted',
            text: fmtDate(rep.range.from) + '  to  ' + fmtDate(rep.range.to) + '  (' + rep.range.days + ' days)' }));

        // Stat chips
        sheet.appendChild(el('div', { class: 'stat-grid' }, [
            stat(rep.stats.avgCompletionPct + '%', 'Avg. completion'),
            stat(rep.stats.daysWithActivity, 'Active days'),
            stat(rep.stats.totalCompletions, 'Tasks completed'),
            stat(rep.stats.avgBehaviorRating === null ? '—' : rep.stats.avgBehaviorRating, 'Avg. behavior (1–5)'),
        ]));

        // Mood summary
        const moods = rep.stats.moodCounts || {};
        const moodKeys = Object.keys(moods);
        if (moodKeys.length) {
            const labelFor = (v) => { const m = MOODS.find(x => x.v === v); return m ? m.label : v; };
            sheet.appendChild(el('p', {}, [
                el('strong', { text: 'Mood across the period: ' }),
                moodKeys.map(k => labelFor(k) + ' ×' + moods[k]).join(',  ')
            ]));
        }

        // Daily completion table
        sheet.appendChild(el('h3', { text: 'Daily completion' }));
        const tbl = el('table');
        tbl.appendChild(el('tr', {}, [
            el('th', { text: 'Date' }), el('th', { text: 'Done / scheduled' }),
            el('th', { text: 'Completion' }), el('th', { text: '' })
        ]));
        rep.daily.forEach(d => {
            const sched = (d.scheduled === undefined ? '' : ' / ' + d.scheduled);
            tbl.appendChild(el('tr', {}, [
                el('td', { text: fmtDateShort(d.day) }),
                el('td', { text: d.completed + sched }),
                el('td', { text: d.percent + '%' }),
                el('td', {}, [el('span', { class: 'bar-mini', style: 'width:' + Math.max(2, d.percent) + 'px' })])
            ]));
        });
        sheet.appendChild(tbl);

        // Task frequency
        if (rep.taskFreq && rep.taskFreq.length) {
            sheet.appendChild(el('h3', { text: 'How often each task was checked off' }));
            const t2 = el('table');
            t2.appendChild(el('tr', {}, [el('th', { text: 'Task' }), el('th', { text: 'Type' }), el('th', { text: 'Times checked off' })]));
            rep.taskFreq.forEach(t => {
                t2.appendChild(el('tr', {}, [
                    el('td', { text: t.title }),
                    el('td', { text: t.type === 'activity' ? 'Activity' : 'Chore' }),
                    el('td', { text: String(t.count) })
                ]));
            });
            sheet.appendChild(t2);
        }

        // Daily narratives
        sheet.appendChild(el('h3', { text: 'Daily notes' }));
        if (!rep.reports.length) {
            sheet.appendChild(el('p', { class: 'muted', text: 'No written daily reports in this range.' }));
        } else {
            rep.reports.forEach(r => {
                const block = el('div', { class: 'report-day' });
                const moodLabel = r.mood ? (MOODS.find(m => m.v === r.mood) || {}).label : '';
                block.appendChild(el('h4', { text: fmtDate(r.day) + (moodLabel ? '  —  ' + moodLabel : '') + (r.behaviorRating ? '  (behavior ' + r.behaviorRating + '/5)' : '') }));
                const addLine = (label, val) => { if (val) block.appendChild(el('p', {}, [el('strong', { text: label + ': ' }), val])); };
                addLine('Highlights', r.highlights);
                addLine('Challenges', r.challenges);
                addLine('Notes', r.notes);
                addLine('Goals', r.goals);
                sheet.appendChild(block);
            });
        }

        // Export actions
        const actions = el('div', { class: 'modal__actions no-print', style: 'margin-top:20px' }, [
            el('button', { class: 'btn btn--ghost', onclick: () => copyBhisText(rep) }, '📋 Copy as text'),
            el('button', { class: 'btn btn--ghost', onclick: () => downloadBhisCsv(rep) }, '⬇️ Download CSV'),
            el('button', { class: 'btn', onclick: () => window.print() }, '🖨️ Print / Save PDF'),
        ]);
        return el('div', {}, [actions, sheet]);
    }

    function bhisAsText(rep) {
        const L = [];
        L.push('PROGRESS REPORT — ' + (rep.child ? rep.child.name : 'Child'));
        L.push(fmtDate(rep.range.from) + ' to ' + fmtDate(rep.range.to) + ' (' + rep.range.days + ' days)');
        L.push('');
        L.push('SUMMARY');
        L.push('  Average completion: ' + rep.stats.avgCompletionPct + '%');
        L.push('  Active days: ' + rep.stats.daysWithActivity + ' of ' + rep.range.days);
        L.push('  Tasks completed: ' + rep.stats.totalCompletions);
        if (rep.stats.avgBehaviorRating !== null) L.push('  Average behavior rating: ' + rep.stats.avgBehaviorRating + '/5');
        const moods = rep.stats.moodCounts || {};
        if (Object.keys(moods).length) {
            L.push('  Mood: ' + Object.keys(moods).map(k => k + ' x' + moods[k]).join(', '));
        }
        L.push('');
        L.push('DAILY COMPLETION');
        rep.daily.forEach(d => L.push('  ' + d.day + ': ' + d.completed + (d.scheduled === undefined ? '' : ' of ' + d.scheduled) + ' done (' + d.percent + '%)'));
        L.push('');
        L.push('DAILY NOTES');
        if (!rep.reports.length) L.push('  (none)');
        rep.reports.forEach(r => {
            const moodLabel = r.mood ? r.mood : '';
            L.push('  ' + r.day + (moodLabel ? '  [' + moodLabel + ']' : '') + (r.behaviorRating ? '  behavior ' + r.behaviorRating + '/5' : ''));
            if (r.highlights) L.push('    Highlights: ' + r.highlights);
            if (r.challenges) L.push('    Challenges: ' + r.challenges);
            if (r.notes) L.push('    Notes: ' + r.notes);
            if (r.goals) L.push('    Goals: ' + r.goals);
        });
        return L.join('\n');
    }
    function copyBhisText(rep) {
        const text = bhisAsText(rep);
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(
                () => toast('Report copied to clipboard.', 'success'),
                () => fallbackCopy(text)
            );
        } else { fallbackCopy(text); }
    }
    function fallbackCopy(text) {
        const ta = el('textarea', { style: 'position:fixed;opacity:0' });
        ta.value = text; document.body.appendChild(ta); ta.select();
        try { document.execCommand('copy'); toast('Report copied.', 'success'); }
        catch (e) { toast('Could not copy automatically.', 'error'); }
        ta.remove();
    }
    function csvCell(v) {
        v = (v === null || v === undefined) ? '' : String(v);
        return '"' + v.replace(/"/g, '""') + '"';
    }
    function downloadBhisCsv(rep) {
        const byDay = {};
        rep.reports.forEach(r => { byDay[r.day] = r; });
        const rows = [['Date', 'Tasks completed', 'Tasks scheduled', 'Completion %', 'Mood', 'Behavior (1-5)', 'Highlights', 'Challenges', 'Notes', 'Goals']];
        rep.daily.forEach(d => {
            const r = byDay[d.day] || {};
            rows.push([d.day, d.completed, (d.scheduled === undefined ? '' : d.scheduled), d.percent, r.mood || '', r.behaviorRating || '',
                r.highlights || '', r.challenges || '', r.notes || '', r.goals || '']);
        });
        const csv = rows.map(r => r.map(csvCell).join(',')).join('\r\n');
        const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' });
        const url = URL.createObjectURL(blob);
        const name = (rep.child ? rep.child.name.replace(/\s+/g, '_') : 'child') + '_' + rep.range.from + '_to_' + rep.range.to + '.csv';
        const a = el('a', { href: url, download: name });
        document.body.appendChild(a); a.click(); a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
        toast('CSV downloaded.', 'success');
    }

    // ---------- Change PIN ----------
    function openChangePin() {
        const cur = el('input', { type: 'password', inputmode: 'numeric', 'aria-label': 'Current PIN' });
        const nw = el('input', { type: 'password', inputmode: 'numeric', 'aria-label': 'New PIN' });
        const content = el('div', {}, [
            el('div', { class: 'field' }, [el('label', { text: 'Current PIN' }), cur]),
            el('div', { class: 'field' }, [el('label', { text: 'New PIN (4–8 digits)' }), nw]),
        ]);
        const actions = el('div', { class: 'modal__actions' }, [
            el('button', { class: 'btn btn--ghost', onclick: closeModal }, 'Cancel'),
            el('button', { class: 'btn', onclick: async () => {
                if (!/^\d{4,8}$/.test(nw.value)) { toast('New PIN must be 4–8 digits.', 'error'); return; }
                try {
                    await api('change_pin', { method: 'POST', body: { current: cur.value, pin: nw.value } });
                    toast('PIN changed.', 'success'); closeModal();
                } catch (err) { toast(err.message, 'error'); }
            } }, 'Change PIN')
        ]);
        openModal('Change parent PIN', content, actions);
    }

    // ==================================================================
    //  BOOT
    // ==================================================================
    async function boot() {
        try {
            const s = await api('status');
            state.family = getFamily();
            state.today = s.today;
            $('#footer-day').textContent = '';
            // Restore a logged-in kid if present
            const saved = localStorage.getItem(CHILD_KEY);
            if (saved) {
                try { state.child = JSON.parse(saved); } catch (e) {}
            }
            if (state.child) {
                if (getToken()) {
                    try { await api('parent_logout', { method: 'POST' }); } catch (e2) { /* ignore */ }
                    setToken(null);
                }
                state.view = 'kid';
            }
            else if (s.isParent) { state.view = 'parent'; state.loginTab = 'parent'; }
            else { state.view = 'login'; }
        } catch (e) {
            $('#view').appendChild(el('div', { class: 'card' }, [
                el('h2', { text: 'Setup needed' }),
                el('p', { text: e.message || 'The app could not reach the server. Check that config.php has your database details.' })
            ]));
            return;
        }
        render();
    }

    // Any tap or key press counts as parent activity.
    ['pointerdown', 'keydown', 'touchstart'].forEach((ev) =>
        document.addEventListener(ev, markParentActivity, { passive: true, capture: true }));
    function checkParentIdle() {
        if (!getToken() || state.view !== 'parent') return;
        if (Date.now() - parentLastActive < PARENT_IDLE_MS) return;
        parentLogout('kid').then(() =>
            toast('The parent area locked after 10 minutes without use. Enter the PIN to go back in.'));
    }
    setInterval(checkParentIdle, 30 * 1000);
    // Coming back to the app after a while also locks it right away.
    document.addEventListener('visibilitychange', () => { if (!document.hidden) checkParentIdle(); });

    document.addEventListener('DOMContentLoaded', boot);
})();
