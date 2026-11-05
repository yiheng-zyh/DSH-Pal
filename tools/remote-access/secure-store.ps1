param(
  [ValidateSet('SetToken','ReadToken','ProtectBytes')][string]$Action='SetToken',
  [string]$Path=(Join-Path $env:LOCALAPPDATA 'DSHPal\remote-access\sakura-token.dpapi')
)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Security
$Path=[IO.Path]::GetFullPath($Path)
if([IO.Path]::GetExtension($Path) -ne '.dpapi'){throw 'Encrypted store must use a .dpapi file'}
function Write-Protected([byte[]]$Bytes) {
  $parent=Split-Path -Parent ([IO.Path]::GetFullPath($Path))
  [IO.Directory]::CreateDirectory($parent)|Out-Null
  $encrypted=[Security.Cryptography.ProtectedData]::Protect($Bytes,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
  $temporary=$Path+'.'+[guid]::NewGuid().ToString('N')+'.tmp'
  try {
    [IO.File]::WriteAllBytes($temporary,$encrypted)
    if([IO.File]::Exists($Path)){[IO.File]::Replace($temporary,$Path,[NullString]::Value)}
    else {[IO.File]::Move($temporary,$Path)}
  } finally {if([IO.File]::Exists($temporary)){[IO.File]::Delete($temporary)}}
}
if($Action -eq 'ReadToken'){
  $raw=[Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($Path),$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
  try {[Console]::Out.Write([Text.Encoding]::UTF8.GetString($raw))}finally{[Array]::Clear($raw,0,$raw.Length)}
  exit 0
}
if($Action -eq 'ProtectBytes'){
  $buffer=New-Object IO.MemoryStream
  [Console]::OpenStandardInput().CopyTo($buffer)
  try {Write-Protected $buffer.ToArray()}finally{$buffer.Dispose()}
  exit 0
}
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$form=New-Object Windows.Forms.Form
$form.Text='DSH Pal - Sakura 本机授权'
$form.ClientSize=New-Object Drawing.Size(570,245)
$form.StartPosition='CenterScreen';$form.TopMost=$true
$form.FormBorderStyle='FixedDialog';$form.MaximizeBox=$false
$label=New-Object Windows.Forms.Label
$label.Text="请从 Sakura 管理面板复制「访问密钥」，粘贴到下面。`r`n密钥只加密保存在当前 Windows 用户的本机目录，不会发送到聊天。"
$label.SetBounds(20,18,530,52);$form.Controls.Add($label)
$tokenBox=New-Object Windows.Forms.TextBox
$tokenBox.UseSystemPasswordChar=$true;$tokenBox.SetBounds(20,82,530,30);$form.Controls.Add($tokenBox)
$status=New-Object Windows.Forms.Label
$status.Text='不要在聊天中粘贴密钥。';$status.SetBounds(20,126,530,36);$form.Controls.Add($status)
$save=New-Object Windows.Forms.Button
$save.Text='加密保存';$save.SetBounds(325,181,105,36);$form.Controls.Add($save)
$cancel=New-Object Windows.Forms.Button
$cancel.Text='取消';$cancel.SetBounds(445,181,105,36);$form.Controls.Add($cancel)
$cancel.Add_Click({$form.Close()})
$save.Add_Click({
  $token=$tokenBox.Text.Trim()
  if($token -notmatch '^[A-Za-z0-9_.-]{8,256}$'){$status.Text='密钥格式不正确，请复制完整的访问密钥。';return}
  try {
    $bytes=[Text.Encoding]::UTF8.GetBytes($token)
    try {Write-Protected $bytes}finally{[Array]::Clear($bytes,0,$bytes.Length)}
    $tokenBox.Clear();$token=$null;$form.DialogResult='OK';$form.Close()
  }catch{$status.Text='加密保存失败，请确认当前 Windows 用户有目录写入权限。'}
})
$form.Add_Shown({$form.Activate();$tokenBox.Focus()})
try {$null=$form.ShowDialog()}finally{$tokenBox.Clear();$form.Dispose()}
