"use strict";

const byId = id => document.getElementById(id);
const text = byId("text");
let port = null;
let state = null;
let waiting = false;
let editing = null;
let deleting = null;
let saved = null;
let restored = null;

function send(command) {
  if (!port || waiting || (state && state.busy)) return;
  waiting = true;
  port.postMessage(JSON.stringify(command));
  render();
}

window.addEventListener("message", function connect(event) {
  if (port || event.data !== "abu-todo-port-v1" || event.ports.length !== 1) return;
  window.removeEventListener("message", connect);
  port = event.ports[0];
  port.onmessage = event => {
    state = JSON.parse(event.data);
    waiting = false;
    if (state.saved && state.saved !== saved) {
      saved = state.saved;
      editing = null;
      deleting = null;
      text.value = "";
    }
    if (state.draft && state.draft !== restored) {
      restored = state.draft;
      const draft = JSON.parse(state.draft);
      editing = draft[0] === "edit" ? draft[1] : null;
      text.value = draft[draft.length - 1];
      deleting = null;
      byId("editor").scrollIntoView({block: "nearest"});
    } else if (!state.draft) {
      restored = null;
    }
    render();
  };
  port.postMessage(JSON.stringify(["read"]));
});

const editable = () => !!state?.writable && !waiting;
const count = value => Array.from(value).length;
const hasText = value => /\P{White_Space}/u.test(value);

function renderEditor() {
  const length = count(text.value);
  const atLimit = !editing && state?.items?.length >= 100;
  byId("editor").hidden = !state?.items && !text.value;
  byId("prompt").textContent = editing ? "编辑待办" : "新增待办";
  byId("count").textContent = atLimit ?
    "最多 100 项，删除一项后可继续新增。" : `${length} / 200 字符`;
  byId("submit").textContent = editing ? "保存修改" : "新增";
  byId("submit").disabled = !editable() || length > 200 || !hasText(text.value) || atLimit;
  byId("close-editor").hidden = !editing && !text.value;
  byId("close-editor").disabled = !editable();
  text.disabled = !editable();
}

function button(label, action, disabled, kind) {
  const node = document.createElement("button");
  node.type = "button";
  node.textContent = label;
  node.disabled = disabled;
  if (kind) node.className = kind;
  node.addEventListener("click", action);
  return node;
}

function renderItem(item) {
  const row = document.createElement("li");
  const label = document.createElement("label");
  const box = document.createElement("input");
  box.type = "checkbox";
  box.checked = item.done;
  box.disabled = !editable();
  box.addEventListener("change", () => {
    const next = box.checked;
    box.checked = item.done; // Confirmed view never predicts completion.
    send(["toggle", item.id, next]);
  });
  const title = document.createElement("span");
  title.textContent = item.text;
  if (item.done) title.className = "done";
  label.append(box, title);
  const actions = document.createElement("div");
  actions.className = "actions";
  if (deleting === item.id) {
    const question = document.createElement("span");
    question.textContent = "确定删除这条待办？";
    actions.append(question,
      button("确认删除", () => send(["delete", item.id]), !editable(), "danger"),
      button("保留", () => { deleting = null; render(); }, !editable()));
  } else {
    actions.append(
      button("编辑", () => {
        if (editing !== item.id && text.value && !window.confirm("替换当前未保存草稿？")) return;
        editing = item.id;
        text.value = item.text;
        renderEditor();
        text.focus();
        text.scrollIntoView({block: "nearest"});
      }, !editable()),
      button("删除", () => { deleting = item.id; render(); }, !editable(), "danger"));
  }
  row.append(label, actions);
  return row;
}

function render() {
  if (!state) return;
  const busy = waiting || state.busy;
  byId("connection").textContent = busy ? "正在与后台通信…" :
    state.writable ? "已连接 · 可修改" : state.online ? "已连接 · 暂不能修改" : "未连接 · 只读";
  byId("snapshot").textContent = state.snapshotTime ? "快照时间：" + state.snapshotTime : "尚无已确认的快照";
  byId("notice").textContent = state.notice || (state.pending === "unknown" ?
    "上次提交尚未确认。未确认时不能编辑；请先刷新连接，再用“重试提交”发送原操作。" :
    state.pending === "conflict" ? "待办已在后台变化。请刷新并处理冲突，草稿不会自动覆盖后台数据。" : "");
  byId("notice").hidden = !byId("notice").textContent;
  byId("refresh").disabled = busy;
  byId("retry").hidden = state.pending !== "unknown";
  byId("retry").disabled = busy || !state.retry;
  byId("recover").hidden = state.pending !== "conflict";
  byId("recover").disabled = busy || !state.recover;
  renderEditor();
  byId("empty").textContent = !state.items ?
    (busy ? "正在读取待办…" : "没有可查看的快照；首次读取需要联网。") :
    state.items.length === 0 ? "还没有待办" : "";
  byId("empty").hidden = !byId("empty").textContent;
  byId("items").replaceChildren(...(state.items || []).map(renderItem));
}

text.addEventListener("input", renderEditor);
byId("refresh").addEventListener("click", () => send(["refresh"]));
byId("retry").addEventListener("click", () => send(["retry"]));
byId("recover").addEventListener("click", () => send(["recoverDraft"]));
byId("submit").addEventListener("click", () => {
  if (editable() && count(text.value) <= 200 && hasText(text.value)) {
    send(editing ? ["edit", editing, text.value] : ["add", text.value]);
  }
});
byId("close-editor").addEventListener("click", () => {
  editing = null;
  text.value = "";
  renderEditor();
  if (restored) send(["clearDraft"]);
});
