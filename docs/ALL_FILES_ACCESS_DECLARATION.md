# "All files access" declaration: draft answers

Play Console → App content → Sensitive app permissions → All files access (`MANAGE_EXTERNAL_STORAGE`).
Copy and adjust. Keep the wording consistent with your store listing, because reviewers compare them.

> Policy caveat: Google's policy lists file management, backup, antivirus, document management and
> on-device search as permitted core uses. Duplicate finders are not named. Frame the app as a
> **file maintenance / file management** tool, and expect a possible rejection. See "If it is rejected".

## Core use case (pick)
File management (accessing, editing and maintaining files and folders outside app-specific storage).

## Why the permission is needed (draft)
> Duplicate Cleaner's core and only purpose is to find and remove identical files anywhere on the
> user's shared storage, across all file types (documents, downloads, archives, media). It must read
> every user folder to compare file contents, and move the redundant copies into a recoverable trash
> folder on the same storage. The Storage Access Framework cannot be used because it requires the user
> to pick files or folders one at a time and cannot enumerate and hash an entire storage volume in one
> pass; MediaStore cannot be used because it exposes only media files, not documents, downloads or
> other file types. Without the permission the app cannot perform its core function.

## What the app does with the access (draft)
> Files are read locally to compute hashes. No file content, name or hash is uploaded or shared.
> Nothing is deleted without the user's confirmation: selected duplicates are moved to a visible
> "DuplicateCleaner Trash" folder and can be restored or permanently deleted by the user.

## Demo video checklist (Play asks for one)
1. Open the app, tap **Scan device**, show the system "All files access" screen and grant it.
2. Show the scan finishing and the duplicate groups.
3. Open a preview, change the keep rule, tap **Move to trash**, confirm.
4. Show the trash card: **Restore all** and **Empty trash**.
5. Say aloud (or caption) what the permission is used for.

## If it is rejected
Ship the fallback instead of arguing:
- **Media-only build:** scan with MediaStore (photos, video, audio), no special permission. The previous
  version of this project did exactly that.
- **Folder-pick build:** let the user choose folders through the Storage Access Framework
  (`ACTION_OPEN_DOCUMENT_TREE`) and scan them with `DocumentFile`. This covers documents without
  the restricted permission, but the file code would need rewriting.
- Offer the all-files version only as a sideloaded APK from GitHub Releases.
