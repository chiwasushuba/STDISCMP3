/* Run against a consumer containing the generated 12s and 2s fixtures.
 * npm ci && npx playwright install chromium && npm run test:browser
 */
const { chromium } = require("playwright");
const axe = require("axe-core");
const assert = require("node:assert/strict");
const fs = require("node:fs/promises");
const path = require("node:path");
const base = process.env.BASE_URL || "http://127.0.0.1:8080";

(async () => {
  const output = path.resolve(".evidence/browser");
  await fs.mkdir(output, { recursive: true });
  const browser = await chromium.launch();
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    recordVideo: { dir: output, size: { width: 1440, height: 1000 } },
  });
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  const checks = [];
  async function check(name, action) {
    await action();
    checks.push(name);
    console.log("PASS", name);
  }
  try {
    await page.goto(base);
    await page.getByText("Consumer online", { exact: true }).waitFor();
    const long = page
      .getByRole("button", { name: "Play twelve-seconds.mp4", exact: true })
      .first();
    const short = page
      .getByRole("button", { name: "Play two-seconds.mp4", exact: true })
      .first();
    await long.waitFor();
    await short.waitFor();
    await page.waitForFunction(() => {
      const videos = [...document.querySelectorAll(".video-open video")].filter(
        (v) => Number.isFinite(v.duration),
      );
      return videos.length >= 2 && videos.every((v) => v.readyState >= 2);
    });
    await page.screenshot({
      path: path.join(output, "desktop.png"),
      fullPage: true,
    });

    await check("Ten-second hover limit stops and resets preview", async () => {
      await long.hover();
      await page.waitForFunction(() => {
        const b = [...document.querySelectorAll(".video-open")].find(
          (e) => e.getAttribute("aria-label") === "Play twelve-seconds.mp4",
        );
        return (
          !b.querySelector("video").paused &&
          b.querySelector("video").currentTime > 0.2
        );
      });
      await page.waitForTimeout(10500);
      assert.equal(
        await long
          .locator("video")
          .evaluate((v) => v.paused && v.currentTime < 0.1),
        true,
      );
    });
    await check(
      "Hover exit, re-entry, and short clips reset correctly",
      async () => {
        await page.locator("h1").hover();
        await long.hover();
        await page.waitForTimeout(350);
        await page.locator("h1").hover();
        assert.equal(
          await long
            .locator("video")
            .evaluate((v) => v.paused && v.currentTime < 0.1),
          true,
        );
        await long.hover();
        await page.waitForTimeout(350);
        assert.equal(
          await long.locator("video").evaluate((v) => !v.paused),
          true,
        );
        await short.hover();
        await page.waitForTimeout(2500);
        assert.equal(
          await short
            .locator("video")
            .evaluate((v) => v.paused && v.currentTime < 0.1),
          true,
        );
      },
    );
    await check(
      "Click opens full playback beyond preview cap; Escape closes player",
      async () => {
        await long.click();
        await page.waitForFunction(
          () => document.getElementById("full-player").readyState >= 2,
        );
        await page.locator("#full-player").evaluate((v) => {
          v.currentTime = 10.5;
          return v.play();
        });
        await page.waitForTimeout(300);
        assert.equal(
          await page
            .locator("#full-player")
            .evaluate((v) => !v.paused && v.currentTime > 10),
          true,
        );
        await page.screenshot({
          path: path.join(output, "player.png"),
          fullPage: true,
        });
        await page.keyboard.press("Escape");
        assert.equal(
          await page.locator("#player-dialog").evaluate((d) => d.open),
          false,
        );
        assert.equal(
          await page
            .locator("#full-player")
            .evaluate((v) => v.paused && !v.hasAttribute("src")),
          true,
        );
      },
    );
    await check("Search has an empty state", async () => {
      await page.getByRole("searchbox").fill("no matching video");
      await page.getByRole("heading", { name: "No matching videos" }).waitFor();
      assert.equal(await page.locator(".card").count(), 0);
      await page.screenshot({
        path: path.join(output, "no-results.png"),
        fullPage: true,
      });
      await page.getByRole("searchbox").fill("");
      await long.waitFor();
    });
    await check("Keyboard opens player and close restores focus", async () => {
      await long.focus();
      await page.keyboard.press("Enter");
      await page.getByRole("button", { name: "Close player" }).click();
      assert.equal(
        await long.evaluate((b) => document.activeElement === b),
        true,
      );
    });
    await check(
      "Unplayable files show a visible recovery message",
      async () => {
        const broken = page
          .getByRole("button", { name: "Play unplayable.mp4", exact: true })
          .first();
        await broken.click();
        await page
          .locator("#player-error")
          .filter({ hasText: "cannot be played" })
          .waitFor();
        await page.getByRole("button", { name: "Close player" }).click();
      },
    );
    await check(
      "Gallery updates after concurrent producer uploads",
      async () => {
        const before = (
          await (await page.request.get(base + "/api/videos")).json()
        ).length;
        const run = require("node:util").promisify(
          require("node:child_process").execFile,
        );
        const uploading = run("java", [
          "-jar",
          "producer/target/producer-1.0.0.jar",
          "--p",
          "2",
          "--folder",
          ".evidence/fixtures/producer-1",
          "--folder",
          ".evidence/fixtures/producer-2",
        ]);
        await page.getByRole("searchbox").fill("twelve");
        await page.getByRole("searchbox").fill("");
        const result = await uploading;
        assert.ok(result.stdout.includes("stored=2 dropped=0 failed=0"));
        await page.waitForFunction(
          (count) => document.querySelectorAll(".card").length === count + 2,
          before,
        );
      },
    );
    await check(
      "Unicode and URL punctuation filenames upload and play",
      async () => {
        const folder = path.resolve(".evidence/fixtures/special-name");
        const name = "clip #50% & '雪'.mp4";
        await fs.mkdir(folder, { recursive: true });
        await fs.copyFile(
          ".evidence/fixtures/producer-2/two-seconds.mp4",
          path.join(folder, name),
        );
        const run = require("node:util").promisify(
          require("node:child_process").execFile,
        );
        const result = await run("java", [
          "-jar",
          "producer/target/producer-1.0.0.jar",
          "--p",
          "1",
          "--folder",
          folder,
        ]);
        assert.ok(result.stdout.includes("stored=1 dropped=0 failed=0"));
        const tile = page
          .getByRole("button", { name: `Play ${name}`, exact: true })
          .first();
        await tile.waitFor();
        await tile.click();
        await page.waitForFunction(() => {
          const video = document.getElementById("full-player");
          return (
            video.readyState >= 2 && !video.paused && video.currentTime > 0
          );
        });
        assert.equal(await page.locator("#player-title").textContent(), name);
        await page.getByRole("button", { name: "Close player" }).click();
      },
    );
    await check(
      "Desktop and mobile have no serious accessibility violations or overflow",
      async () => {
        for (const viewport of [
          { width: 1440, height: 1000 },
          { width: 390, height: 844 },
        ]) {
          await page.setViewportSize(viewport);
          await page.addScriptTag({ content: axe.source });
          const result = await page.evaluate(async () => {
            const results = await axe.run();
            return results.violations.filter((v) =>
              ["serious", "critical"].includes(v.impact),
            );
          });
          assert.deepEqual(
            result.map((v) => ({
              id: v.id,
              nodes: v.nodes.map((n) => n.target),
            })),
            [],
          );
          assert.equal(
            await page.evaluate(
              () => document.documentElement.scrollWidth <= innerWidth,
            ),
            true,
          );
          await page.screenshot({
            path: path.join(output, `layout-${viewport.width}.png`),
            fullPage: true,
          });
        }
      },
    );
    await check("Reduced motion keeps navigation usable", async () => {
      await page.emulateMedia({ reducedMotion: "reduce" });
      await short.click();
      await page.getByRole("button", { name: "Close player" }).click();
    });
    await check(
      "Offline state is visible and reconnects without losing library",
      async () => {
        await context.setOffline(true);
        await page.getByText("Consumer offline", { exact: true }).waitFor();
        await page.screenshot({
          path: path.join(output, "offline.png"),
          fullPage: true,
        });
        await context.setOffline(false);
        await page.getByText("Consumer online", { exact: true }).waitFor();
        assert.ok((await page.locator(".card").count()) >= 2);
      },
    );
    await check("HTTP errors preserve the library and recover", async () => {
      const before = await page.locator(".card").count();
      await page.route("**/api/videos", (route) =>
        route.fulfill({ status: 500, body: "simulated server error" }),
      );
      try {
        await page.getByText("Consumer offline", { exact: true }).waitFor();
        assert.equal(await page.locator(".card").count(), before);
      } finally {
        await page.unroute("**/api/videos");
      }
      await page.getByText("Consumer online", { exact: true }).waitFor();
      assert.equal(await page.locator(".card").count(), before);
    });
    await check(
      "An empty library renders correctly on first load",
      async () => {
        const empty = await context.newPage();
        empty.on("pageerror", (error) => errors.push(error.message));
        await empty.route("**/api/videos", (route) =>
          route.fulfill({ json: [] }),
        );
        try {
          await empty.goto(base);
          await empty.getByText("Consumer online", { exact: true }).waitFor();
          await empty
            .getByRole("heading", { name: "Your next frame starts here" })
            .waitFor();
          assert.equal(await empty.locator(".card").count(), 0);
          assert.equal(await empty.locator("#count").textContent(), "0");
        } finally {
          await empty.close();
        }
      },
    );
    await check(
      "Untrusted filename text cannot create HTML elements",
      async () => {
        const list = await (
          await page.request.get(base + "/api/videos")
        ).json();
        const name = '<img src=x onerror="window.filenameExecuted=true">.mp4';
        const escaped = await context.newPage();
        escaped.on("pageerror", (error) => errors.push(error.message));
        await escaped.route("**/api/videos", (route) =>
          route.fulfill({ json: [{ ...list[0], name }] }),
        );
        try {
          await escaped.goto(base);
          await escaped
            .getByRole("button", { name: `Play ${name}`, exact: true })
            .waitFor();
          assert.equal(
            await escaped.locator(".card-title").textContent(),
            name,
          );
          assert.equal(await escaped.locator(".card img").count(), 0);
          assert.equal(
            await escaped.evaluate(() => window.filenameExecuted),
            undefined,
          );
        } finally {
          await escaped.close();
        }
      },
    );
    assert.deepEqual(errors, []);
    await fs.writeFile(
      path.join(output, "results.json"),
      JSON.stringify({ base, checks, pageErrors: errors }, null, 2),
    );
  } finally {
    await context.close();
    await browser.close();
  }
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
