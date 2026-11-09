// The note editor of the Android app: the web client's CodeMirror editor
// (@ivynote/editor, live preview) in a WebView, driven over a small bridge.
// Kept in step with the Apple client's EditorWeb/main.ts.
//
// Kotlin → JS: window.ivy.load / setBody / setTitles / setKinds / setTheme /
//              setLanguage / setReadonly / focus / command / scrollToLine / insert
// JS → Kotlin: IvyAndroid.postMessage(JSON.stringify({ type, ... }))
//   ready              the page can take a document
//   change {id, body}  the user edited the note
//   open {id, title}   a wiki link was followed ('' id: no such note)
//   focus {focused}    the editor gained or lost the keyboard
//   scroll {line}      the 0-based line at the top of the view (the outline)
//   error {message}    an uncaught script error, for the debug log
//   protection {id, protected, locked}
//                      whether the open note has a password, and is locked
//
//   pick {kind}        the insert menu asks for a photo ('image') or a file ('file')
//
// command() also takes: insert (the "/" menu at the cursor, for a ＋ button),
// due (date picker for the task at the cursor),
// protect / unprotect / changePassword / lock (guide 8.4). A protected note's
// body only ever leaves this page sealed.

import { autocompletion, closeBrackets, closeBracketsKeymap, completionKeymap } from '@codemirror/autocomplete';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import { markdown, markdownLanguage } from '@codemirror/lang-markdown';
import { highlightSelectionMatches, search, searchKeymap } from '@codemirror/search';
import { Compartment, EditorState, Transaction } from '@codemirror/state';
import { EditorView, keymap, placeholder } from '@codemirror/view';
import { insertLink, insertText, insertWiki, toggleLinePrefix, toggleWrap } from '@ivynote/editor/commands';
import { livePreview, type PreviewOptions } from '@ivynote/editor/livePreview';
import { baseTheme, markdownHighlight } from '@ivynote/editor/theme';
import { wikiCompletion } from '@ivynote/editor/wikiComplete';
import { dateCompletion, openDuePicker } from '@ivynote/editor/dates';
import { insertMenu, insertMenuLook, openInsertMenu, slashCompletion } from '@ivynote/editor/insert';
import { selectionToolbar } from '@ivynote/editor/bubble';
import { Protection } from '@ivynote/editor/protection';
import { normalizeTitle } from '@ivynote/core/markdown/links';
import { renderMarkdown } from '@ivynote/core/markdown/render';

type Msg = { type: string; [k: string]: unknown };

interface TitleEntry {
	id: string;
	title: string;
	folder?: string;
}

interface LoadPayload {
	id: string;
	body: string;
	readonly?: boolean;
	fontSize?: number;
	focus?: boolean;
}

declare global {
	interface Window {
		IvyAndroid?: { postMessage(m: string): void };
		ivy: Record<string, (...a: never[]) => unknown>;
	}
}

function post(m: Msg) {
	window.IvyAndroid?.postMessage(JSON.stringify(m));
}

// --- state shared with the preview options ---

let noteId = '';
let titles: TitleEntry[] = [];
let byTitle = new Map<string, string>();
let kinds: Record<string, string> = {};
let lastEmitted = '';
let view: EditorView | null = null;
const roConf = new Compartment();
const placeholderConf = new Compartment();

// --- the interface language (setLanguage): the placeholder, and the words
// formatting wraps around an empty selection ---

const words = {
	zh: { placeholder: '开始写作…', bold: '加粗', italic: '斜体', strike: '删除线' },
	en: { placeholder: 'Start writing…', bold: 'bold', italic: 'italic', strike: 'strikethrough' }
};
let lang: keyof typeof words = 'zh';
const w = () => words[lang];

/** Same-origin path the native scheme handler answers with the attachment. */
function resource(id: string, thumb?: boolean): string {
	return `/res/${encodeURIComponent(id)}${thumb ? '?thumb=1' : ''}`;
}

const options: PreviewOptions = {
	resolveResource: resource,
	resourceKind: (id) => kinds[id] ?? 'image',
	resolveTitle: (title) => byTitle.get(normalizeTitle(title.split('#')[0].split('|')[0])) ?? '',
	openNote: (id, title) => post({ type: 'open', id, title }),
	renderBlock: (md) =>
		renderMarkdown(md, {
			resolveResource: resource,
			resourceKind: (id) => kinds[id],
			resolveTitle: (t) => options.resolveTitle(t),
			noteHref: () => '#'
		})
};

function completions() {
	return titles.filter((t) => t.id !== noteId && t.title).map((t) => ({ title: t.title, detail: t.folder }));
}

function extensions(readonly: boolean) {
	return [
		history(),
		closeBrackets(),
		search({ top: true }),
		highlightSelectionMatches(),
		markdown({ base: markdownLanguage, addKeymap: true }),
		markdownHighlight,
		baseTheme,
		EditorView.lineWrapping,
		placeholderConf.of(placeholder(w().placeholder)),
		// "/" opens the insert menu; photos and files are picked by the app.
		autocompletion({ override: [slashCompletion({ hosts: ['image', 'file'], host: (kind) => post({ type: 'pick', kind }) }), dateCompletion, wikiCompletion(completions)], icons: false, activateOnTyping: true, maxRenderedOptions: 40, ...insertMenuLook }),
		insertMenu(),
		selectionToolbar(),
		keymap.of([
			{ key: 'Mod-b', run: (v) => toggleWrap(v, '**', w().bold) },
			{ key: 'Mod-i', run: (v) => toggleWrap(v, '*', w().italic) },
			{ key: 'Mod-e', run: (v) => toggleWrap(v, '`', 'code') },
			{ key: 'Mod-Shift-x', run: (v) => toggleWrap(v, '~~', w().strike) },
			{ key: 'Mod-k', run: insertLink },
			{ key: 'Mod-Shift-k', run: insertWiki },
			{ key: 'Mod-Alt-1', run: (v) => toggleLinePrefix(v, '# ') },
			{ key: 'Mod-Alt-2', run: (v) => toggleLinePrefix(v, '## ') },
			{ key: 'Mod-Alt-3', run: (v) => toggleLinePrefix(v, '### ') },
			{ key: 'Mod-Shift-7', run: (v) => toggleLinePrefix(v, '1. ') },
			{ key: 'Mod-Shift-8', run: (v) => toggleLinePrefix(v, '- ') },
			{ key: 'Mod-Shift-9', run: (v) => toggleLinePrefix(v, '- [ ] ') },
			{ key: 'Mod-Shift-d', run: (v) => (openDuePicker(v), true) },
			{ key: 'Mod-/', run: openInsertMenu },
			{ key: 'Mod-Shift-h', run: (v) => toggleWrap(v, '==') },
			...closeBracketsKeymap,
			...completionKeymap,
			...searchKeymap,
			...historyKeymap,
			indentWithTab,
			...defaultKeymap
		]),
		livePreview(options),
		EditorView.contentAttributes.of({ class: 'cm-live' }),
		roConf.of(EditorState.readOnly.of(readonly)),
		EditorView.updateListener.of((u) => {
			if (u.focusChanged) post({ type: 'focus', focused: u.view.hasFocus });
			if (!u.docChanged) return;
			// Text put in from outside (sync, another device) is not sent back.
			if (u.transactions.every((tr) => tr.annotation(Transaction.remote))) return;
			// A protected note leaves the page sealed (guide 8.4).
			const body = protection.edited(u.state.doc.toString());
			lastEmitted = body;
			post({ type: 'change', id: noteId, body });
		})
	];
}

const host = document.getElementById('editor')!;
let loadReadonly = false;

/** Puts text that changed elsewhere into the editor with the minimal diff,
 *  so the cursor stays where it is. */
function replaceText(v: string) {
	if (!view) return;
	const cur = view.state.doc.toString();
	if (v === cur) return;
	let start = 0;
	while (start < cur.length && start < v.length && cur[start] === v[start]) start++;
	let endCur = cur.length;
	let endNew = v.length;
	while (endCur > start && endNew > start && cur[endCur - 1] === v[endNew - 1]) {
		endCur--;
		endNew--;
	}
	view.dispatch({ changes: { from: start, to: endCur, insert: v.slice(start, endNew) }, annotations: Transaction.remote.of(true) });
}

// Password-protected notes: the page holds the plaintext only while
// unlocked; the app only ever sees the sealed body.
const protection = new Protection({
	lockHost: host,
	show(text) {
		const state = EditorState.create({ doc: text, extensions: extensions(loadReadonly) });
		if (view) view.setState(state);
		else view = new EditorView({ state, parent: host });
		view.scrollDOM.scrollTop = 0;
	},
	hide() {
		view?.destroy();
		view = null;
	},
	replace: replaceText,
	text: () => view?.state.doc.toString() ?? null,
	emit(body) {
		lastEmitted = body;
		post({ type: 'change', id: noteId, body });
	},
	state: (s) => post({ type: 'protection', id: noteId, ...s }),
	readonly: () => !view || view.state.readOnly
});

// The page scrolls, not CodeMirror. Report the line at the top of the
// window, once per frame, so the outline can mark where the reader is.
let lastTop = -1;
let topQueued = false;
function reportTop() {
	topQueued = false;
	if (!view) return;
	// A little below the top edge: a heading just scrolled to counts as read.
	const block = view.lineBlockAtHeight(48 - view.documentTop);
	const line = view.state.doc.lineAt(Math.min(block.from, view.state.doc.length)).number - 1;
	if (line === lastTop) return;
	lastTop = line;
	post({ type: 'scroll', line });
}
window.addEventListener(
	'scroll',
	() => {
		if (topQueued) return;
		topQueued = true;
		requestAnimationFrame(reportTop);
	},
	{ passive: true }
);

window.ivy = {
	/** Opens a note: a fresh state, so undo history never crosses notes. */
	load(p: LoadPayload) {
		noteId = p.id;
		lastEmitted = p.body;
		loadReadonly = !!p.readonly;
		if (p.fontSize) document.documentElement.style.setProperty('--editor-size', `${p.fontSize}px`);
		
		// The editor, or the lock panel of a protected note.
		protection.load(p.body);
		document.scrollingElement?.scrollTo(0, 0);
		lastTop = -1;
		reportTop();
		if (p.focus && !protection.isLocked) window.ivy.focus(true);
	},

	/** A body that changed elsewhere (sync, merge). Applies the minimal diff
	 *  so the cursor stays where it is. */
	setBody(id: string, v: string) {
		if (id !== noteId || v === lastEmitted) return;
		lastEmitted = v;
		protection.external(v);
	},

	setTitles(list: TitleEntry[]) {
		titles = list;
		byTitle = new Map();
		// Most recently updated first: the first match wins.
		for (const t of list) {
			const k = normalizeTitle(t.title);
			if (k && !byTitle.has(k)) byTitle.set(k, t.id);
		}
		// Re-render links whose target may have appeared.
		view?.dispatch({});
	},

	setKinds(map: Record<string, string>) {
		const changed = JSON.stringify(map) !== JSON.stringify(kinds);
		kinds = map;
		// Embeds drawn before their kind was known re-render as video, audio or a file card.
		if (changed) view?.dispatch({});
	},

	/** Inserts text at the cursor (an attachment embed: on its own line). */
	insert(text: string, block = true) {
		if (!view || view.state.readOnly) return;
		insertText(view, text, block);
	},

	/** `zh` or `en`, as the app's interface. */
	setLanguage(l: string) {
		lang = l === 'en' ? 'en' : 'zh';
		document.documentElement.lang = lang === 'zh' ? 'zh-CN' : 'en';
		view?.dispatch({ effects: placeholderConf.reconfigure(placeholder(w().placeholder)) });
	},

	setTheme(dark: boolean) {
		document.documentElement.dataset.theme = dark ? 'dark' : 'light';
	},

	setReadonly(ro: boolean) {
		view?.dispatch({ effects: roConf.reconfigure(EditorState.readOnly.of(ro)) });
	},

	focus(atEnd = false) {
		if (!view) return;
		view.focus();
		if (atEnd) view.dispatch({ selection: { anchor: view.state.doc.length }, scrollIntoView: true });
	},

	/** Jumps to a 0-based line (the outline), its heading at the top. */
	scrollToLine(line: number, focus = false) {
		if (!view) return;
		const l = view.state.doc.line(Math.min(Math.max(1, line + 1), view.state.doc.lines));
		view.dispatch({ selection: { anchor: l.from }, effects: EditorView.scrollIntoView(l.from, { y: 'start', yMargin: 24 }) });
		if (focus) view.focus();
	},

	/** Formatting from the native toolbar. */
	command(name: string) {
		switch (name) {
			case 'protect': void protection.protect(); return;
			case 'unprotect': void protection.unprotect(); return;
			case 'changePassword': void protection.changePassword(); return;
			case 'lock': protection.lock(); return;
		}
		if (!view) return;
		switch (name) {
			case 'due': openDuePicker(view); return;
			case 'insert': openInsertMenu(view); return;
			case 'bold': toggleWrap(view, '**', w().bold); break;
			case 'italic': toggleWrap(view, '*', w().italic); break;
			case 'strike': toggleWrap(view, '~~', w().strike); break;
			case 'code': toggleWrap(view, '`', 'code'); break;
			case 'h1': toggleLinePrefix(view, '# '); break;
			case 'h2': toggleLinePrefix(view, '## '); break;
			case 'h3': toggleLinePrefix(view, '### '); break;
			case 'list': toggleLinePrefix(view, '- '); break;
			case 'ordered': toggleLinePrefix(view, '1. '); break;
			case 'task': toggleLinePrefix(view, '- [ ] '); break;
			case 'quote': toggleLinePrefix(view, '> '); break;
			case 'link': insertLink(view); break;
			case 'wiki': insertWiki(view); break;
			case 'rule': insertText(view, '---', true); break;
			case 'undo': import('@codemirror/commands').then((m) => view && m.undo(view)); return;
			case 'redo': import('@codemirror/commands').then((m) => view && m.redo(view)); return;
			case 'blur': view.contentDOM.blur(); return;
		}
		view.focus();
	}
} as unknown as Window['ivy'];

window.addEventListener('error', (e) => post({ type: 'error', message: String(e.message) }));
// Media that fails to load (offline, deleted attachment) is worth a log line.
document.addEventListener(
	'error',
	(e) => {
		const t = e.target as HTMLElement | null;
		if (t && 'src' in t && t !== (window as unknown)) post({ type: 'log', message: `failed to load ${(t as HTMLImageElement).src}` });
	},
	true
);
post({ type: 'ready' });
