"use strict";

const { app, version } = document.body.dataset;
const other = app === "A" ? "B" : "A";
const expected = "owner-" + app;
const otherOrigin = "https://" + other.toLowerCase() + ".abu.invalid";
const cacheA = "abu-probe-a-offline-v1";
const cacheB = "abu-probe-b-only";
const result = { origin: location.origin, version, secureContext: isSecureContext };
const target = document.getElementById("result");

function bounded(promise, ms = 2200) {
  let timer;
  return Promise.race([promise, new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error("timeout")), ms);
  })]).finally(() => clearTimeout(timer));
}

function database() {
  return new Promise((resolve, reject) => {
    const open = indexedDB.open("abu-probe", 1);
    open.onupgradeneeded = () => open.result.createObjectStore("data");
    open.onerror = () => reject(open.error);
    open.onsuccess = () => {
      const db = open.result;
      const tx = db.transaction("data", "readwrite");
      const store = tx.objectStore("data");
      const owner = store.get("owner");
      const peer = store.get("only-" + other);
      owner.onsuccess = () => {
        if (owner.result === undefined) {
          store.put(expected, "owner");
          store.put(expected, "only-" + app);
        }
      };
      tx.oncomplete = () => {
        db.close();
        resolve([owner.result ?? null, peer.result ?? null]);
      };
      tx.onabort = () => {
        db.close();
        reject(tx.error);
      };
    };
  });
}

async function tryFetch(url) {
  try {
    const response = await bounded(fetch(url));
    return "http-" + response.status;
  } catch (error) {
    return error.message === "timeout" ? "timeout" : "rejected";
  }
}

function frame(url, inspect) {
  const view = document.createElement("iframe");
  view.hidden = true;
  return bounded(new Promise(resolve => {
    view.onload = () => {
      try {
        resolve(inspect(view));
      } catch (error) {
        resolve(error.name);
      }
    };
    view.onerror = () => resolve("load-error");
    view.src = url;
    document.body.append(view);
  })).catch(() => "timeout").finally(() => view.remove());
}

async function storage() {
  const prior = localStorage.getItem("owner");
  const peer = localStorage.getItem("only-" + other);
  if (prior === null) {
    localStorage.setItem("owner", expected);
    localStorage.setItem("only-" + app, expected);
  }
  result.local = { prior, owner: localStorage.getItem("owner"), peer, ok: (prior === null || prior === expected) && peer === null };
  const [dbPrior, dbPeer] = await bounded(database(), 6000);
  const [dbOwner] = await bounded(database(), 6000);
  result.indexedDB = { prior: dbPrior, owner: dbOwner, peer: dbPeer, ok: dbOwner === expected && (dbPrior === null || dbPrior === expected) && dbPeer === null };
}

async function worker() {
  if (!("serviceWorker" in navigator)) return { status: "missing-api" };
  let stage = "register";
  try {
    const registration = await bounded(navigator.serviceWorker.register("/worker.js", { updateViaCache: "none" }));
    stage = "ready";
    await bounded(navigator.serviceWorker.ready);
    stage = "controller";
    if (!navigator.serviceWorker.controller) {
      await bounded(new Promise(resolve => navigator.serviceWorker.addEventListener("controllerchange", resolve, { once: true })));
    }
    stage = "cache";
    const cache = await bounded(caches.open(cacheA));
    const stored = await bounded(cache.match("/offline.html"));
    const response = await bounded(fetch("/offline.html"));
    const cachedDocument = await frame("/offline.html", view => view.contentDocument.querySelector("h1").textContent);
    return {
      status: "controlled", scope: registration.scope, cached: stored?.headers.get("X-Abu-Source"),
      fetch: response.headers.get("X-Abu-Source"), http: response.status, document: cachedDocument,
      ok: registration.scope === location.origin + "/" && stored?.headers.get("X-Abu-Source") === "worker-cache" &&
          response.status === 200 && response.headers.get("X-Abu-Source") === "worker-cache" && cachedDocument === "Offline cache document"
    };
  } catch (error) {
    return { status: stage + "-" + (error.message === "timeout" ? "timeout" : error.name) };
  }
}

async function run() {
  try {
    await storage();
    const checks = [
      ["peerFetch", otherOrigin + "/index.html"],
      ["outside", "https://blocked.invalid/fetch"],
      ["loopback", "https://127.0.0.1:8080/probe"],
      ["cleartextLoopback", "http://127.0.0.1:8080/probe"],
      ["path", "/private.html"],
      ["file", "file:///abu-probe-nothing"],
      ["content", "content://local.abu.probe.nothing/index.html"]
    ];
    const [fetches, peerFrame] = await Promise.all([
      Promise.all(checks.map(async ([key, url]) => [key, await tryFetch(url)])),
      frame(otherOrigin + "/index.html", view => String(view.contentWindow.localStorage.getItem("owner")))
    ]);
    result.fetch = Object.fromEntries(fetches);
    result.peerFrame = peerFrame;
    result.popupAutomaticBlocked = window.open("https://blocked.invalid/automatic") === null;
    if ("caches" in window) {
      if (app === "B") await bounded(caches.open(cacheB));
      result.cacheIsolation = { peerCacheVisible: await bounded(caches.has(app === "A" ? cacheB : cacheA)) };
    }
    if (app === "A") {
      result.worker = await worker();
    } else {
      let crossRegistration = "missing-api";
      if ("serviceWorker" in navigator) {
        try {
          await bounded(navigator.serviceWorker.register(otherOrigin + "/worker.js"));
          crossRegistration = "unexpected-success";
        } catch (error) {
          crossRegistration = error.message === "timeout" ? "timeout" : error.name;
        }
      }
      result.worker = { status: "not-requested", crossRegistration };
    }
  } catch (error) {
    result.probeError = error.message === "timeout" ? "timeout" : error.name;
  }
  target.textContent = JSON.stringify(result, null, 1);
  target.dataset.finished = "yes";
}

run();
