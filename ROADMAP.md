# Roadmap

Ordered by expected value for a freemium cleaner. Build one, measure, then pick the next.

1. ~~Similar-photo detection~~ **Built (v1.1).** Next steps: burst/crop tolerance (needs a different feature
   than the current gradient hash), similar videos, and a "favourites" signal so the best pick also considers
   sharpness.
2. **Storage breakdown and large-file finder.** "What is using my space?" Free to view, Pro to act.
   Reuses the existing file walk.
3. **Foreground-service scanning and scheduled reminders.** Long scans survive leaving the app;
   periodic reminders bring users back.
4. **Hash cache.** Store hashes by path + size + modified time so rescans are fast.
5. **Basic analytics** (Firebase or Play Console funnel): scan finished, paywall shown, purchase, ad watched.
6. **EEA consent (UMP SDK)** for ads.
7. **Cloud duplicates (Drive and similar).** Large effort; needs sign-in and API access. Later bet.

Not planned: duplicate contacts (sensitive permission), "RAM boosters" and similar fake cleaners.
