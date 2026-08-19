Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$sc = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
Write-Host ('Screen: ' + $sc.Width + 'x' + $sc.Height)
$bm = New-Object System.Drawing.Bitmap($sc.Width, $sc.Height)
$gr = [System.Drawing.Graphics]::FromImage($bm)
$gr.CopyFromScreen($sc.X, $sc.Y, 0, 0, $sc.Size)
$pt = 'd:\Chunkup\screen_f3_on.png'
$bm.Save($pt, [System.Drawing.Imaging.ImageFormat]::Png)
$gr.Dispose()
$bm.Dispose()
$sz = (Get-Item $pt).Length
Write-Host ('Saved: ' + $pt + ' Size: ' + $sz)
