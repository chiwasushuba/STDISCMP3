"use strict";
const $ = (id) => document.getElementById(id);
let videos = [];
let signature = "";
let initialized = false;
let stopPreviews = [];
const dialog = $("player-dialog");
const full = $("full-player");
const codecMessage =
  "This video cannot be played in this browser. Try an MP4 with H.264 video and AAC audio, or a compatible WebM.";

function sizeLabel(bytes) {
  return bytes < 1048576
    ? `${(bytes / 1024).toFixed(1)} KB`
    : `${(bytes / 1048576).toFixed(1)} MB`;
}

function render() {
  stopPreviews.forEach((stop) => stop());
  stopPreviews = [];
  $("gallery").replaceChildren();
  const query = $("search").value.trim().toLocaleLowerCase();
  const visible = videos.filter((v) =>
    v.name.toLocaleLowerCase().includes(query),
  );
  $("count").textContent = String(videos.length);
  $("empty").hidden = visible.length > 0;
  $("empty").querySelector("h3").textContent = query
    ? "No matching videos"
    : "Your next frame starts here";
  $("empty").querySelector("p").textContent = query
    ? "Try another filename or clear your search."
    : "Run a producer to upload videos. Completed uploads appear here automatically.";
  for (const item of visible) {
    const card = document.createElement("article");
    card.className = "card";
    const button = document.createElement("button");
    button.type = "button";
    button.className = "video-open";
    button.setAttribute("aria-label", `Play ${item.name}`);
    const preview = document.createElement("video");
    const src = `/api/videos/${encodeURIComponent(item.id)}/content`;
    preview.src = src;
    preview.muted = true;
    preview.playsInline = true;
    preview.preload = "metadata";
    preview.addEventListener("loadedmetadata", () => {
      // Seek just inside the video to decode a still frame without starting playback.
      if (Number.isFinite(preview.duration) && preview.duration > 0) {
        preview.currentTime = Math.min(0.05, preview.duration / 2);
      }
    });
    preview.setAttribute("aria-hidden", "true");
    const badge = document.createElement("span");
    badge.className = "preview-label";
    badge.textContent = "10 SEC PREVIEW";
    const icon = document.createElement("span");
    icon.className = "play-icon";
    icon.textContent = "▷";
    icon.setAttribute("aria-hidden", "true");
    button.append(preview, badge, icon);
    const info = document.createElement("div");
    info.className = "card-info";
    const title = document.createElement("h3");
    title.className = "card-title";
    title.textContent = item.name;
    title.title = item.name;
    const meta = document.createElement("div");
    meta.className = "card-meta";
    const details = document.createElement("span");
    details.textContent = `${item.name.split(".").pop().toUpperCase()} · ${sizeLabel(item.size)}`;
    const date = document.createElement("time");
    date.dateTime = item.uploadedAt;
    date.textContent = new Date(item.uploadedAt).toLocaleDateString(undefined, {
      month: "short",
      day: "numeric",
    });
    meta.append(details, date);
    const error = document.createElement("p");
    error.className = "card-error";
    error.hidden = true;
    info.append(title, meta, error);
    card.append(button, info);
    let generation = 0;
    let timer;
    let hovered = false;
    function stop() {
      generation++;
      clearTimeout(timer);
      preview.pause();
      if (preview.readyState > 0) preview.currentTime = 0;
      button.classList.remove("previewing");
    }
    async function start() {
      stop();
      if (dialog.open) return;
      const token = generation;
      try {
        await preview.play();
        if (token !== generation) {
          // A later preview owns playback; only pause when there is no active interaction.
          if (!hovered && document.activeElement !== button) preview.pause();
          return;
        }
        button.classList.add("previewing");
        timer = setTimeout(stop, 10000);
      } catch (e) {
        if (token === generation && e.name !== "AbortError") {
          error.textContent = codecMessage;
          error.hidden = false;
        }
      }
    }
    button.addEventListener("pointerenter", () => {
      hovered = true;
      start();
    });
    button.addEventListener("pointerleave", () => {
      hovered = false;
      stop();
    });
    button.addEventListener("focus", start);
    button.addEventListener("blur", stop);
    preview.addEventListener("timeupdate", () => {
      if (preview.currentTime >= 10) stop();
    });
    preview.addEventListener("ended", stop);
    preview.addEventListener("error", () => {
      error.textContent = codecMessage;
      error.hidden = false;
    });
    button.addEventListener("click", () => {
      stopPreviews.forEach((stop) => stop());
      $("player-title").textContent = item.name;
      $("player-error").textContent = "";
      full.src = src;
      dialog.showModal();
      full.play().catch((e) => {
        if (e.name !== "AbortError")
          $("player-error").textContent = codecMessage;
      });
    });
    stopPreviews.push(stop);
    $("gallery").append(card);
  }
}

function closePlayer() {
  dialog.close();
}
$("close-player").addEventListener("click", closePlayer);
dialog.addEventListener("close", () => {
  full.pause();
  full.removeAttribute("src");
  full.load();
});
dialog.addEventListener("click", (event) => {
  if (event.target === dialog) closePlayer();
});
full.addEventListener("error", () => {
  if (full.hasAttribute("src")) $("player-error").textContent = codecMessage;
});
$("search").addEventListener("input", render);
document.addEventListener("visibilitychange", () => {
  if (document.hidden) {
    stopPreviews.forEach((stop) => stop());
    full.pause();
  }
});

async function refresh() {
  try {
    const [listResponse, statusResponse] = await Promise.all([
      fetch("/api/videos", {
        signal: AbortSignal.timeout(5000),
        cache: "no-store",
      }),
      fetch("/api/status", {
        signal: AbortSignal.timeout(5000),
        cache: "no-store",
      }),
    ]);
    if (!listResponse.ok || !statusResponse.ok)
      throw new Error("Could not fetch library");
    const [list, status] = await Promise.all([
      listResponse.json(),
      statusResponse.json(),
    ]);
    for (const field of ["completed", "active", "queued", "dropped", "failed"])
      $(field).textContent = status[field];
    $("workers").textContent = `/ ${status.consumers}`;
    $("capacity").textContent = `/ ${status.capacity}`;
    $("connection").textContent = "Consumer online";
    $("connection").className = "connection online";
    $("notice").textContent = "";
    const next = JSON.stringify(list);
    if (!initialized || next !== signature) {
      videos = list;
      signature = next;
      initialized = true;
      render();
    }
  } catch (e) {
    $("connection").textContent = "Consumer offline";
    $("connection").className = "connection offline";
    $("notice").textContent =
      "Could not reach the consumer. Check that it is running; reconnecting automatically.";
  } finally {
    setTimeout(refresh, 2000);
  }
}
refresh();
