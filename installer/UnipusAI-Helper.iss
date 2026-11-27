; =====================================================================
; Inno Setup 6 script for UnipusAI-Helper (Windows installer).
;
; All variant-specific values come from /D defines supplied by
; scripts/build_variant.py, e.g.:
;   ISCC.exe installer\UnipusAI-Helper.iss ^
;     /DAppVersion=3.9.19 /DBuildMode=release ^
;     /DMyAppExe=UnipusAI-Helper.exe /DAppDirName=UnipusAI-Helper ^
;     /DAppId=B7E4A1C2-3D5F-4A68-9B01-2C3D4E5F6A70 ^
;     /DSourceDir=C:\...\dist\UnipusAI-Helper ^
;     /DSetupBaseName=UnipusAI-Helper-Setup-3.9.19-win-x64-release ^
;     /DOutputDir=C:\...\installer
; The #ifndef defaults make the script directly runnable by hand as well.
; =====================================================================

#ifndef AppVersion
  #define AppVersion "3.9.19"
#endif
#ifndef BuildMode
  #define BuildMode "release"
#endif
#ifndef MyAppExe
  #define MyAppExe "UnipusAI-Helper.exe"
#endif
#ifndef AppDirName
  #define AppDirName "UnipusAI-Helper"
#endif
#ifndef AppId
  #define AppId "B7E4A1C2-3D5F-4A68-9B01-2C3D4E5F6A70"
#endif
#ifndef SourceDir
  #define SourceDir "..\dist\UnipusAI-Helper"
#endif
#ifndef SetupBaseName
  #define SetupBaseName "UnipusAI-Helper-Setup"
#endif
#ifndef OutputDir
  #define OutputDir "installer"
#endif

#define MyAppName "UnipusAI-Helper"
#define MyAppPublisher "UnipusAI-Helper Contributors"
#define MyAppURL "https://github.com/jason195421/UnipusAI-Helper/"

[Setup]
AppId={#AppId}
AppName={#MyAppName}
AppVersion={#AppVersion}
AppVerName={#MyAppName} {#AppVersion} ({#BuildMode})
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
VersionInfoVersion={#AppVersion}
VersionInfoCompany={#MyAppPublisher}
VersionInfoProductName={#MyAppName}
VersionInfoProductTextVersion={#AppVersion} ({#BuildMode})

; 用户级安装：不需要管理员权限，安装目录始终可写。冻结后的程序把
; config.json 与 logs/ 写在 exe 同目录，装到 Program Files 会因只读而失败。
DefaultDirName={localappdata}\{#AppDirName}
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
DisableProgramGroupPage=yes
DefaultGroupName={#MyAppName} ({#BuildMode})
DisableDirPage=auto
UsedUserAreasWarning=no

OutputDir={#OutputDir}
OutputBaseFilename={#SetupBaseName}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
; WinUI 3 / Fluent 风格资源：向导大图(164x314)、小图(55x55)、安装包图标
WizardImageFile=WizardImage.bmp
WizardSmallImageFile=WizardSmallImage.bmp
SetupIconFile=UnipusAI-Helper.ico
; 强制显示欢迎页，展示「欢迎安装 UnipusAI-Helper-NEXT」
DisableWelcomePage=no
ArchitecturesInstallIn64BitMode=x64
ArchitecturesAllowed=x64
UninstallDisplayIcon={app}\{#MyAppExe}
UninstallDisplayName={#MyAppName} {#AppVersion} ({#BuildMode})
CloseApplications=yes
RestartApplications=no

[Languages]
; 自定义简体中文语言文件（UTF-8 BOM），向导全部文字均为中文
Name: "chinesesimplified"; MessagesFile: "ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MyAppName} ({#BuildMode})"; Filename: "{app}\{#MyAppExe}"; WorkingDir: "{app}"
Name: "{autodesktop}\{#MyAppName} ({#BuildMode})"; Filename: "{app}\{#MyAppExe}"; Tasks: desktopicon; WorkingDir: "{app}"

[Run]
Filename: "{app}\{#MyAppExe}"; Description: "{cm:LaunchProgram,{#MyAppName}}"; Flags: nowait postinstall skipifsilent

; =====================================================================
; WinUI 3 / Fluent 风格向导美化（Pascal Script）
;   - Segoe UI 字体、Fluent 浅色 Mica 背景
;   - 强调蓝 #0F6CBD（TColor 为 $00BBGGRR → $00BD6C0F）
;   - 圆角按钮（CreateRoundRectRgn + SetWindowRgn）
; =====================================================================
[Code]
function CreateRoundRectRgn(nLeftRect, nTopRect, nRightRect, nBottomRect, nWidthEllipse, nHeightEllipse: Integer): THandle;
  external 'CreateRoundRectRgn@gdi32.dll stdcall';
function SetWindowRgn(hWnd: THandle; hRgn: THandle; bRedraw: BOOL): Integer;
  external 'SetWindowRgn@user32.dll stdcall';

procedure ApplyRoundedButton(Btn: TNewButton);
var
  R: THandle;
begin
  R := CreateRoundRectRgn(0, 0, Btn.Width, Btn.Height, 8, 8);
  if R <> 0 then
    SetWindowRgn(Btn.Handle, R, True);
end;

procedure InitializeWizard();
begin
  // 全局：Segoe UI + Fluent 浅色 Mica 背景
  WizardForm.Font.Name := 'Segoe UI';
  WizardForm.Color := $00F9F9F9;
  WizardForm.MainPanel.Color := $00F9F9F9;
  WizardForm.WelcomePage.Color := $00F9F9F9;
  WizardForm.InnerPage.Color := $00F9F9F9;

  // 欢迎页：强调蓝标题「欢迎安装 UnipusAI-Helper-NEXT」
  WizardForm.WelcomeLabel1.Font.Name := 'Segoe UI';
  WizardForm.WelcomeLabel1.Font.Size := 14;
  WizardForm.WelcomeLabel1.Font.Style := [fsBold];
  WizardForm.WelcomeLabel1.Font.Color := $00BD6C0F;
  WizardForm.WelcomeLabel2.Font.Color := $00404040;

  // 内页标题、说明、完成页与状态文字
  WizardForm.PageNameLabel.Font.Name := 'Segoe UI';
  WizardForm.PageNameLabel.Font.Style := [fsBold];
  WizardForm.PageNameLabel.Font.Color := $001A1A1A;
  WizardForm.PageDescriptionLabel.Font.Color := $00606060;
  WizardForm.FinishedHeadingLabel.Font.Color := $00BD6C0F;
  WizardForm.FinishedLabel.Font.Color := $001A1A1A;
  WizardForm.StatusLabel.Font.Color := $00606060;

  // 按钮：Segoe UI + 圆角
  WizardForm.NextButton.Font.Name := 'Segoe UI';
  WizardForm.NextButton.Font.Style := [fsBold];
  WizardForm.BackButton.Font.Name := 'Segoe UI';
  WizardForm.CancelButton.Font.Name := 'Segoe UI';
  ApplyRoundedButton(WizardForm.NextButton);
  ApplyRoundedButton(WizardForm.BackButton);
  ApplyRoundedButton(WizardForm.CancelButton);
end;
