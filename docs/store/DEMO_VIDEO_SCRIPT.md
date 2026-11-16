# Demo video for the "All files access" declaration

Play wants a short (about 60-90 s) screen recording of the real app on a real phone, showing the permission prompt and how the access is used. Record it from the **Play internal-testing build** (or the APK), not a mock-up.

## Before you record
- Phone: your OnePlus. Swipe down > **Screen Recorder**. Turn on the microphone if you want to speak; otherwise use the on-screen captions below added in any video editor (CapCut, etc.).
- Revoke access first so the prompt appears: Settings > Apps > Cloneout > Permissions > Files and media / All files access > **Don't allow**.
- Put 3-4 duplicate files in a folder (a PDF copied twice, a photo copied twice, a renamed copy) so the scan finds something.
- Do Not Disturb on, battery above 50%, hide personal notifications and files you don't want shown.

## Shots (caption / what to say)
| Time | What to show | Caption or voice-over |
|---|---|---|
| 0:00-0:05 | Home screen of Cloneout | "Cloneout finds and removes duplicate files on your device." |
| 0:05-0:15 | Tap **Scan now**. The system "All files access" screen opens. Show the toggle for Cloneout, switch it **on**, press back. | "To compare every file in every folder, the app needs All files access. MediaStore and the file picker can't do this for documents and downloads." |
| 0:15-0:30 | Scan runs, then the **Identical** results appear. | "Files are read on the device to compare their content. Nothing is uploaded." |
| 0:30-0:40 | Tap one group, then the preview of a file. | "The user previews files before deciding." |
| 0:40-0:55 | Tap the clean button and confirm the dialog. | "Nothing is deleted without confirmation. Copies go to a recoverable Trash." |
| 0:55-1:10 | Open the **Trash** tab. Restore one file, then show **Empty Trash**. | "Everything can be restored, or removed for good by the user." |
| 1:10-1:15 | Back to Home. | "Cloneout uses the permission only for finding and cleaning duplicate files." |

## After recording
1. Trim the start and end; keep it under 2 minutes.
2. Upload to YouTube as **Unlisted** (not Private; reviewers must be able to open it).
3. Paste the link in Play Console > App content > Sensitive app permissions > All files access > video link.
4. Make sure the video matches the declaration text, because reviewers compare them.
