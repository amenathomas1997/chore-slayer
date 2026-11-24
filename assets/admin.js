/* Chore Slayer site admin: list, rename, re-code, reset PIN, sign out, delete families. */
(function () {
    'use strict';

    const TOKEN_KEY = 'chore_admin_token';
    const $ = (s) => document.querySelector(s);

    function el(tag, props, children) {
        const node = document.createElement(tag);
        props = props || {};
        for (const k in props) {
            const v = props[k];
            if (v === null || v === undefined || v === false) continue;
            if (k === 'class') node.className = v;
            else if (k === 'text') node.textContent = v;
            else if (k.slice(0, 2) === 'on' && typeof v === 'function') node.addEventListener(k.slice(2).toLowerCase(), v);
            else if (v === true) node.setAttribute(k, '');
            else node.setAttribute(k, v);
        }
        (Array.isArray(children) ? children : children == null ? [] : [children]).forEach((c) => {
            if (c == null || c === false) return;
            node.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
        });
        return node;
    }

    // The admin token lives only in this tab and is gone when it closes.
    const getToken = () => { try { return sessionStorage.getItem(TOKEN_KEY); } catch (e) { return null; } };
    const setToken = (t) => { try { t ? sessionStorage.setItem(TOKEN_KEY, t) : sessionStorage.removeItem(TOKEN_KEY); } catch (e) { /* ignore */ } };

    async function api(action, body) {
        const init = { method: body ? 'POST' : 'GET', headers: {} };
        const token = getToken();
        if (token) init.headers['X-Admin-Token'] = token;
        if (body) { init.headers['Content-Type'] = 'application/json'; init.body = JSON.stringify(body); }
        const res = await fetch('api.php?action=' + encodeURIComponent(action), init);
        let data = {};
        try { data = await res.json(); } catch (e) { /* non-JSON */ }
        if (!res.ok) {
            if (res.status === 401 && action !== 'admin_login') { setToken(null); showLogin('Your admin session ended. Please sign in again.'); }
            const err = new Error(data.message || 'Something went wrong.');
            err.status = res.status;
            throw err;
        }
        return data;
    }

    let toastTimer = null;
    function toast(msg, kind) {
        const t = $('#toast');
        t.textContent = msg;
        t.className = 'toast' + (kind ? ' toast--' + kind : '');
        t.hidden = false;
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => { t.hidden = true; }, kind === 'error' ? 8000 : 4000);
    }
    const announce = (msg) => { $('#sr-status').textContent = msg; };

    function header(signedIn) {
        const box = $('#header-actions');
        box.innerHTML = '';
        if (signedIn) {
            box.appendChild(el('button', { class: 'header-btn', onclick: signOut }, 'Sign out'));
        }
    }

    // ------------------------------------------------------------ sign in
    async function showLogin(message) {
        header(false);
        const view = $('#view');
        view.innerHTML = '';
        let configured = true;
        try { configured = (await api('admin_status')).configured; } catch (e) { /* show the form anyway */ }
        const card = el('section', { class: 'card', 'aria-labelledby': 'login-h' }, [
            el('h2', { id: 'login-h', class: 'panel-title', text: 'Admin sign-in' }),
        ]);
        if (!configured) {
            card.appendChild(el('p', { text: 'No admin password is set yet. Set one on the server with tools/admin/set-admin-password.sh, then reload this page.' }));
            view.appendChild(card);
            return;
        }
        if (message) card.appendChild(el('p', { class: 'muted', role: 'status', text: message }));
        const pw = el('input', { id: 'admin-pw', type: 'password', autocomplete: 'current-password', required: true });
        const form = el('form', { novalidate: true }, [
            el('div', { class: 'field' }, [el('label', { for: 'admin-pw', text: 'Admin password' }), pw]),
            el('button', { class: 'btn', type: 'submit' }, 'Sign in'),
        ]);
        form.addEventListener('submit', async (ev) => {
            ev.preventDefault();
            try {
                const r = await api('admin_login', { password: pw.value });
                setToken(r.token);
                pw.value = '';
                showFamilies();
            } catch (err) {
                toast(err.message, 'error');
                pw.select();
                pw.focus();
            }
        });
        card.appendChild(form);
        view.appendChild(card);
        pw.focus();
    }

    async function signOut() {
        try { await api('admin_logout', {}); } catch (e) { /* ignore */ }
        setToken(null);
        showLogin('Signed out.');
    }

    // ------------------------------------------------------------ families
    let families = [];
    let filter = '';

    async function showFamilies(focusId) {
        header(true);
        try {
            families = (await api('admin_families')).families;
        } catch (err) {
            if (err.status !== 401) toast(err.message, 'error');
            return;
        }
        const view = $('#view');
        view.innerHTML = '';
        const search = el('input', { id: 'fam-search', type: 'search', value: filter, 'aria-describedby': 'fam-count' });
        search.addEventListener('input', () => { filter = search.value; renderTable(); });
        view.appendChild(el('h2', { class: 'panel-title', text: 'Families' }));
        view.appendChild(el('div', { class: 'admin-toolbar' }, [
            el('div', { class: 'field' }, [el('label', { for: 'fam-search', text: 'Search by name or code' }), search]),
            el('button', { class: 'btn btn--ghost', type: 'button', onclick: () => showFamilies() }, 'Refresh'),
        ]));
        view.appendChild(el('p', { id: 'fam-count', class: 'muted', role: 'status' }));
        view.appendChild(el('div', { id: 'fam-table' }));
        renderTable();
        const target = focusId && document.getElementById(focusId);
        (target || $('#main')).focus();
    }

    function matches(f) {
        const q = filter.trim().toLowerCase();
        if (!q) return true;
        return (f.name || '').toLowerCase().includes(q) || f.code.toLowerCase().includes(q.replace(/[^a-z0-9]/g, ''));
    }

    const day = (ts) => (ts ? String(ts).slice(0, 10) : 'Never');

    function renderTable() {
        const shown = families.filter(matches);
        const count = families.length === 1 ? '1 family' : families.length + ' families';
        $('#fam-count').textContent = shown.length === families.length ? count + ' in total.' : 'Showing ' + shown.length + ' of ' + count + '.';
        const box = $('#fam-table');
        box.innerHTML = '';
        if (!shown.length) { box.appendChild(el('p', { class: 'empty', text: 'No families match.' })); return; }
        const rows = shown.map((f) => {
            const label = f.name || 'Unnamed family';
            const act = (text, fn, cls) => el('button', {
                class: 'btn btn--small' + (cls ? ' ' + cls : ''), type: 'button',
                id: text.replace(/\W/g, '') + '-' + f.id,
                'aria-label': text + ': ' + label, onclick: () => fn(f),
            }, text);
            return el('tr', {}, [
                el('th', { scope: 'row' }, label),
                el('td', { 'data-label': 'Code' }, el('span', { class: 'admin-code' }, f.code)),
                el('td', { class: 'num', 'data-label': 'Children' }, String(f.children)),
                el('td', { class: 'num', 'data-label': 'Tasks' }, String(f.tasks)),
                el('td', { 'data-label': 'Last check-off' }, day(f.lastActive)),
                el('td', { class: 'num', 'data-label': 'Parent sign-ins open' }, String(f.signedIn)),
                el('td', { 'data-label': 'Created' }, day(f.createdAt)),
                el('td', {}, el('div', { class: 'admin-actions' }, [
                    act('Edit', editFamily),
                    act('Reset PIN', resetPin),
                    act('Sign out devices', signOutFamily),
                    act('Delete', deleteFamily, 'btn--danger'),
                ])),
            ]);
        });
        box.appendChild(el('table', { class: 'admin-table' }, [
            el('caption', { text: 'All families' }),
            el('thead', {}, el('tr', {}, ['Family', 'Code', 'Children', 'Tasks', 'Last check-off', 'Parent sign-ins open', 'Created', 'Actions']
                .map((h) => el('th', { scope: 'col' }, h)))),
            el('tbody', {}, rows),
        ]));
        // On narrow screens the CSS turns rows into cards (display:block), which makes
        // some browsers drop table semantics; state the roles explicitly.
        const t = box.querySelector('.admin-table');
        t.setAttribute('role', 'table');
        t.querySelectorAll('thead, tbody').forEach((g) => g.setAttribute('role', 'rowgroup'));
        t.querySelectorAll('tr').forEach((r) => r.setAttribute('role', 'row'));
        t.querySelectorAll('th').forEach((c) => c.setAttribute('role', 'columnheader'));
        t.querySelectorAll('td').forEach((c) => c.setAttribute('role', 'cell'));
    }

    // ------------------------------------------------------------ dialogs
    // A native modal <dialog>: traps focus, closes on Escape, and returns focus.
    function dialog(title, fields, submitText, danger, onSubmit, opener) {
        const d = el('dialog', { class: 'admin-dialog', 'aria-labelledby': 'dlg-h' });
        const err = el('p', { class: 'field-err', role: 'alert', id: 'dlg-err' });
        const form = el('form', { method: 'dialog', novalidate: true }, [
            el('h2', { id: 'dlg-h', class: 'panel-title', text: title }),
            ...fields,
            err,
            el('div', { class: 'modal__actions' }, [
                el('button', { class: 'btn btn--ghost', type: 'button', onclick: () => d.close() }, 'Cancel'),
                el('button', { class: 'btn' + (danger ? ' btn--danger' : ''), type: 'submit' }, submitText),
            ]),
        ]);
        form.addEventListener('submit', async (ev) => {
            ev.preventDefault();
            err.textContent = '';
            const firstInput = form.querySelector('input, select, textarea');
            if (firstInput) firstInput.removeAttribute('aria-invalid');
            try {
                const msg = await onSubmit();
                d.close();
                toast(msg, 'success');
                showFamilies(opener && opener.id);
            } catch (e2) {
                err.textContent = e2.message;
                // Tie the error to the field so it is read with it, and go back there.
                if (firstInput) {
                    firstInput.setAttribute('aria-invalid', 'true');
                    firstInput.setAttribute('aria-describedby', ((firstInput.getAttribute('aria-describedby') || '') + ' dlg-err').trim());
                    firstInput.focus();
                }
            }
        });
        d.appendChild(form);
        d.addEventListener('close', () => { d.remove(); if (opener && document.body.contains(opener)) opener.focus(); });
        document.body.appendChild(d);
        d.showModal();
        const first = d.querySelector('input');
        if (first) first.focus();
    }

    const field = (id, label, input, help) => el('div', { class: 'field' }, [
        el('label', { for: id, text: label }), input,
        help ? el('p', { id: id + '-help', class: 'help', text: help }) : null,
    ]);

    function editFamily(f) {
        const name = el('input', { id: 'ed-name', type: 'text', value: f.name || '', maxlength: 120 });
        const code = el('input', { id: 'ed-code', type: 'text', value: f.code, maxlength: 16, autocapitalize: 'characters', spellcheck: 'false', 'aria-describedby': 'ed-code-help' });
        dialog('Edit ' + (f.name || 'family'), [
            field('ed-name', 'Family name', name),
            field('ed-code', 'Family code', code, '4 to 16 letters or numbers. Changing it means devices must reconnect with the new code.'),
        ], 'Save', false, async () => {
            const r = await api('admin_update_family', { id: f.id, name: name.value, code: code.value });
            return 'Saved. Family code is ' + r.code + '.';
        }, document.activeElement);
    }

    function resetPin(f) {
        const p1 = el('input', { id: 'rp-1', type: 'password', inputmode: 'numeric', autocomplete: 'new-password', maxlength: 8, 'aria-describedby': 'rp-1-help' });
        const p2 = el('input', { id: 'rp-2', type: 'password', inputmode: 'numeric', autocomplete: 'new-password', maxlength: 8 });
        dialog('Reset PIN for ' + (f.name || f.code), [
            field('rp-1', 'New parent PIN', p1, '4 to 8 digits. Every parent sign-in for this family ends.'),
            field('rp-2', 'Type the new PIN again', p2),
        ], 'Reset PIN', false, async () => {
            if (!/^\d{4,8}$/.test(p1.value)) throw new Error('The PIN must be 4 to 8 digits.');
            if (p1.value !== p2.value) throw new Error('The two PINs do not match.');
            await api('admin_reset_pin', { id: f.id, pin: p1.value });
            return 'PIN reset for ' + (f.name || f.code) + '.';
        }, document.activeElement);
    }

    async function signOutFamily(f) {
        const opener = document.activeElement;
        try {
            await api('admin_signout_family', { id: f.id });
            toast('Signed out every parent device for ' + (f.name || f.code) + '.', 'success');
            showFamilies(opener && opener.id);
        } catch (err) { toast(err.message, 'error'); }
    }

    function deleteFamily(f) {
        const confirmIn = el('input', { id: 'del-code', type: 'text', autocomplete: 'off', spellcheck: 'false', 'aria-describedby': 'del-code-help' });
        dialog('Delete ' + (f.name || 'family') + '?', [
            el('p', { text: 'This permanently deletes the family, its ' + f.children + ' children, every task, check-off and report. It cannot be undone.' }),
            field('del-code', 'Type the family code to confirm', confirmIn, 'The code is ' + f.code + '.'),
        ], 'Delete forever', true, async () => {
            await api('admin_delete_family', { id: f.id, confirmCode: confirmIn.value });
            return (f.name || f.code) + ' was deleted.';
        }, $('#main'));
    }

    // ------------------------------------------------------------ start
    if (getToken()) showFamilies(); else showLogin();
    announce('');
})();
