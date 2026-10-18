' 隐藏窗口跑 verify-all.ps1（不弹控制台、不抢焦点）
Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
base = fso.GetParentFolderName(WScript.ScriptFullName)
out = sh.ExpandEnvironmentStrings("%TEMP%") & "\dsh-verify-out.txt"
args = ""
For Each a In WScript.Arguments
  args = args & " " & a
Next
cmd = "powershell -NoProfile -ExecutionPolicy Bypass -File """ & base & "\verify-all.ps1""" & args
sh.Run "cmd /c " & cmd & " > """ & out & """ 2>&1", 0, True
