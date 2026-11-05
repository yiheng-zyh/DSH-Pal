param()
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$data=[Console]::In.ReadToEnd() | ConvertFrom-Json
$form=New-Object Windows.Forms.Form
$form.Text='DSH Pal - 扫码连接';$form.ClientSize=New-Object Drawing.Size(460,535)
$form.StartPosition='CenterScreen';$form.TopMost=$true;$form.FormBorderStyle='FixedDialog';$form.MaximizeBox=$false
$imageBytes=[Convert]::FromBase64String($data.png)
$memory=New-Object IO.MemoryStream(,$imageBytes)
$picture=New-Object Windows.Forms.PictureBox
$picture.Image=[Drawing.Image]::FromStream($memory);$picture.SetBounds(20,15,420,420);$form.Controls.Add($picture)
$label=New-Object Windows.Forms.Label
$label.Text=$data.origin+"`r`n请使用 DSH Pal 扫描。请勿公开这个临时二维码。"
$label.SetBounds(20,445,420,65);$form.Controls.Add($label)
$timer=New-Object Windows.Forms.Timer;$timer.Interval=1000
$timer.Add_Tick({if([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() -ge [long]$data.expiresAt){$form.Close()}});$timer.Start()
try{$null=$form.ShowDialog()}finally{$timer.Dispose();$picture.Image.Dispose();$memory.Dispose();[Array]::Clear($imageBytes,0,$imageBytes.Length);$form.Dispose()}
