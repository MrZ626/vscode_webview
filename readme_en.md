Translated by GitHub Copilot & polished by MrZ_26

# VS Code Webview (For Android)

> **Declaration: This project is my (MrZ_26) first experimental Vibe Coding project**  
> **All technical details were completed by Claude Opus 4.6**

## Project Motivation

I want to code on my Android tablet with external keyboard

Keybinding conflicts on browser, probably a standalone app could avoid that

## Project Features

The webview opens the vscode.dev link, that's it

Since it's not a browser environment, a webview alone doesn't have local file read/write capabilities, that's not good.

So I asked AI to bridge the file operations (open file/folder requests), which implemented basic file read/write functionality.

Note: CRUD still requires some work, and AI made several mistakes during the process.

## FAQ

- Various online features don't work, how can the app still require internet?
  - IDK, but I think it's worse if you accidentally press Ctrl+W in the browser and close the entire app.
  - I'd rather use this offline app that requires internet.
  - This APK is only 30KB in total, it's so cooooool!
- Since I can't log in, how to import settings and keybindings?
  1. Open VS Code on your PC, open the command palette and find `Open User Settings (JSON)` to open `settings.json`, then copy this file and `keybindings.json` (in the same directory) to your Android device.
  2. On your Android device, open this app, open `settings.json` from the command palette (it should be in the app's internal directory, IDK the details), select all the content from the copied file, paste & save.
  3. Keep `settings.json` open, although you can't open its folder, notice the path `User > settings.json` displayed at the bottom of the tab. Click on `User`, a dropdown will appear with `keybindings.json` (initially it won't be there, you need to manually change a keybinding to make it appear), click it, paste & save.
- Can't open media files like images?
  - IDK, debugging shows that VS Code doesn't go through same API as the text file read/write process when opening images.
  - Whatever, we're here to type, if you really want to view images, use the system file manager.
- Is the release APK safe?
  - I don't know if you care, just mentioning it.
  - I signed the APK with my own key, for those who don't want to compile it themselves.
  - If you're really concerned, you can compile it yourself, you'll need the Android SDK, follow the steps in `build.sh` manually, no Gradle, that's probably how the 30KB size came about.

## Disclaimer (?)

I'm just an indie game developer, I can only use what the game engine provides to make some toys. This project involves operating system stuff, I can at most understand the idea but can't handwrite it myself.

I am completely unable to take responsibility for this project, if anything goes wrong, don't blame me. But what could go wrong? Deleting the entire local storage? Anyway, be careful not to give the app access to the entire root directory.

Remember to back up important projects, this app is only for temporary code/text editing.

## Final Words

Watching AI work is so fun, the first human-AI collaboration was a complete success
