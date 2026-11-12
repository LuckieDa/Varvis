@echo off
setlocal
for %%I in ("%~dp0..\..") do set "VARVIS_CODE_ROOT=%%~fI"
set "VARVIS_VSCODE_EXE=D:\VScode\Microsoft VS Code\Code.exe"
if not exist "%VARVIS_VSCODE_EXE%" (
  echo VS Code executable not found: %VARVIS_VSCODE_EXE%
  exit /b 1
)
start "" "%VARVIS_VSCODE_EXE%" --user-data-dir "%VARVIS_CODE_ROOT%\local\private\vscode-remote-profile" --extensions-dir "%VARVIS_CODE_ROOT%\local\tools\vscode\extensions" --new-window --remote "ssh-remote+varvis-lab-server" "/home/ecs-user"
endlocal
