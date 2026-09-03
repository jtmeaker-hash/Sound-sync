Termux / Debian / Antigravity CLI Notes

1. Open Termux

Start Termux normally.

If you are currently inside another shell and want to get back to Termux:

exit

You may need to run "exit" more than once depending on which shell you are inside.

---

2. Boot Debian with proot-distro

From the normal Termux shell:

proot-distro login debian

You should now be inside Debian.

Check with:

cat /etc/os-release

---

3. Add Antigravity CLI to PATH

If "agy" says "command not found", run:

export PATH="$HOME/.local/bin:$PATH"

Check Antigravity is available:

agy --version

Example:

1.1.24

---

4. Start Antigravity CLI

Run:

agy

This opens the Antigravity CLI.

---

5. Exit Antigravity

To leave Antigravity, try:

Ctrl + C

If required, press it again.

Once Antigravity closes, you should return to the Debian terminal.

To leave Debian and return to normal Termux:

exit

---

6. Install Nano Editor

Nano is useful for writing and pasting very large AI prompts without accidentally submitting part of the prompt early.

Inside Debian:

apt update
apt install nano -y

---

7. Set Nano as the Default Editor

Run:

export EDITOR=nano
export VISUAL=nano

To make this permanent:

echo 'export EDITOR=nano' >> ~/.bashrc
echo 'export VISUAL=nano' >> ~/.bashrc

Then reload the shell:

source ~/.bashrc

Check:

echo $EDITOR

It should say:

nano

---

8. Create a File for a Large Antigravity Prompt

For example:

nano prompt.md

Nano will open a blank editor.

Paste the entire prompt into Nano.

Using a file prevents Antigravity from trying to execute the prompt before the whole thing has finished pasting.

---

9. Save a File in Nano

After pasting the prompt:

Press:

Ctrl + O

"O" means Write Out / Save.

Nano will show the filename at the bottom.

Press:

Enter

The file is now saved.

---

10. Exit Nano

Press:

Ctrl + X

This returns you to the terminal.

So the basic sequence is:

Ctrl + O
Enter
Ctrl + X

Remember

SAVE = Ctrl + O
CONFIRM = Enter
EXIT = Ctrl + X

---

11. Reopen an Existing Prompt

If the file already exists:

nano prompt.md

Edit it, then save again with:

Ctrl + O
Enter
Ctrl + X

---

12. View the Prompt Without Editing It

Run:

cat prompt.md

For a very large prompt:

less prompt.md

Press:

q

to exit "less".

---

13. Copy the Prompt Back Into Antigravity

You can display the prompt with:

cat prompt.md

Then copy it and paste it into Antigravity.

A safer workflow for large prompts is:

nano prompt.md

Paste everything.

Save:

Ctrl + O
Enter
Ctrl + X

Then start Antigravity:

agy

Now the complete prompt is safely stored in "prompt.md" in case anything goes wrong.

---

14. Useful Prompt Files

Instead of constantly overwriting one prompt, create different files.

Examples:

nano bug-fix.md

nano new-feature.md

nano sound-sync-audit.md

nano merge-branches.md

List your saved files with:

ls

---

15. Recommended SoundSync Workflow

Boot Termux.

Start Debian:

proot-distro login debian

Go to the SoundSync repo:

cd /path/to/Sound-sync

Check the repo:

git status

Create or edit your AI prompt:

nano prompt.md

Paste the complete prompt.

Save and exit:

Ctrl + O
Enter
Ctrl + X

Start Antigravity:

agy

---

Quick Reference

Start Debian

proot-distro login debian

Start Antigravity

agy

Fix "agy: command not found"

export PATH="$HOME/.local/bin:$PATH"

Open Large Prompt Editor

nano prompt.md

Save Nano

Ctrl + O
Enter

Exit Nano

Ctrl + X

Exit Antigravity

Ctrl + C

Exit Debian

exit

Return to SoundSync Repo

cd /path/to/Sound-sync

Check Git Status

git status

---

Important

Do not run:

proot-distro login debian

from inside an unnecessary root shell.

Run "proot-distro" from the normal Termux environment whenever possible.

If the prompt is very large, write it into "prompt.md" first instead of directly pasting it into an interactive AI CLI. This avoids parts of the prompt being interpreted before the entire paste has completed.
