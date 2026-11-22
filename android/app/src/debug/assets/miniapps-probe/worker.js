"use strict";

const cacheName = "abu-probe-a-offline-v1";
const offlineUrl = new URL("/offline.html", self.location.origin).href;

self.addEventListener("install", event => {
  event.waitUntil((async () => {
    const cache = await caches.open(cacheName);
    // This document has no native route. Its only successful source is this cache.
    await cache.put(offlineUrl, new Response("<!doctype html><h1>Offline cache document</h1>", {
      headers: {
        "Content-Type": "text/html; charset=utf-8",
        "Content-Security-Policy": "default-src 'none'",
        "X-Abu-Source": "worker-cache"
      }
    }));
    await self.skipWaiting();
  })());
});

self.addEventListener("activate", event => event.waitUntil(self.clients.claim()));

self.addEventListener("fetch", event => {
  if (event.request.method === "GET" && event.request.url === offlineUrl) {
    event.respondWith(caches.open(cacheName).then(cache => cache.match(offlineUrl)).then(
      response => response ?? new Response("Offline cache miss", { status: 503 })
    ));
  }
});
