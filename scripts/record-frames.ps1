param(
    [Parameter(Mandatory = $true)][string]$Hwnd,
    [Parameter(Mandatory = $true)][string]$OutDir,
    [int]$Fps = 4,
    [int]$MaxSeconds = 540
)

Add-Type -AssemblyName System.Drawing
$code = @'
using System;
using System.Drawing;
using System.Runtime.InteropServices;
public class CapLoop {
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  public struct RECT { public int Left, Top, Right, Bottom; }
  public static bool Shot(IntPtr h, string path) {
    RECT r; if (!GetWindowRect(h, out r)) return false;
    int w = r.Right - r.Left, ht = r.Bottom - r.Top;
    if (w <= 0 || ht <= 0) return false;
    using (var bmp = new Bitmap(w, ht)) {
      using (var g = Graphics.FromImage(bmp)) {
        IntPtr hdc = g.GetHdc();
        bool ok = PrintWindow(h, hdc, 2);
        g.ReleaseHdc(hdc);
        if (!ok) return false;
      }
      bmp.Save(path, System.Drawing.Imaging.ImageFormat.Jpeg);
      return true;
    }
  }
}
'@
Add-Type -TypeDefinition $code -ReferencedAssemblies System.Drawing -ErrorAction Stop

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$stopFlag = Join-Path (Split-Path -Parent $OutDir) 'stop-capture'
if (Test-Path $stopFlag) { Remove-Item $stopFlag -Force }
$handle = [IntPtr][int64]$Hwnd
$interval = [int](1000 / [Math]::Max(1, $Fps))
$sw = [Diagnostics.Stopwatch]::StartNew()
$i = 0
while ($sw.Elapsed.TotalSeconds -lt $MaxSeconds -and -not (Test-Path $stopFlag)) {
    $path = Join-Path $OutDir ('frame_{0:d5}.jpg' -f $i)
    [CapLoop]::Shot($handle, $path) | Out-Null
    $i++
    Start-Sleep -Milliseconds $interval
}
"frames=$i seconds=$([int]$sw.Elapsed.TotalSeconds)"
