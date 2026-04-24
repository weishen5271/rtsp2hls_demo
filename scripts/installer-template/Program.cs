using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Windows.Forms;

namespace RtspHlsLocalPluginInstaller
{
    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            try
            {
                Install();
                MessageBox.Show(
                    "RtspHlsLocalPlugin 安装完成，服务已启动。\n默认地址：http://127.0.0.1:18080",
                    "RtspHlsLocalPlugin",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Information
                );
            }
            catch (Exception ex)
            {
                MessageBox.Show(
                    "安装失败：" + ex.Message,
                    "RtspHlsLocalPlugin",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error
                );
            }
        }

        private static void Install()
        {
            string installRoot = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "RtspHlsLocalPlugin"
            );
            string installDir = Path.Combine(installRoot, "app");
            string tempRoot = Path.Combine(Path.GetTempPath(), "rtsp-hls-plugin-install-" + Guid.NewGuid().ToString("N"));
            string archivePath = Path.Combine(tempRoot, "payload.zip");
            string extractedDir = Path.Combine(tempRoot, "RtspHlsLocalPlugin");

            Directory.CreateDirectory(tempRoot);
            ExtractPayload(archivePath);
            ZipFile.ExtractToDirectory(archivePath, tempRoot);

            if (!Directory.Exists(extractedDir))
            {
                throw new InvalidOperationException("安装包内容不完整：" + extractedDir);
            }

            foreach (Process process in Process.GetProcessesByName("RtspHlsLocalPlugin"))
            {
                try
                {
                    process.Kill();
                    process.WaitForExit(3000);
                }
                catch
                {
                }
            }

            if (Directory.Exists(installDir))
            {
                Directory.Delete(installDir, true);
            }

            Directory.CreateDirectory(installRoot);
            Directory.Move(extractedDir, installDir);

            string exePath = Path.Combine(installDir, "RtspHlsLocalPlugin.exe");
            if (!File.Exists(exePath))
            {
                throw new InvalidOperationException("未找到可执行文件：" + exePath);
            }

            Process.Start(new ProcessStartInfo
            {
                FileName = exePath,
                WorkingDirectory = installDir,
                UseShellExecute = true
            });
        }

        private static void ExtractPayload(string archivePath)
        {
            Assembly assembly = Assembly.GetExecutingAssembly();
            string resourceName = assembly.GetManifestResourceNames()
                .FirstOrDefault(name => name.EndsWith("payload.zip", StringComparison.OrdinalIgnoreCase));

            if (resourceName == null)
            {
                throw new InvalidOperationException("未找到安装包资源 payload.zip");
            }

            using (Stream resourceStream = assembly.GetManifestResourceStream(resourceName))
            {
                if (resourceStream == null)
                {
                    throw new InvalidOperationException("无法读取安装包资源 payload.zip");
                }

                using (FileStream fileStream = File.Create(archivePath))
                {
                    resourceStream.CopyTo(fileStream);
                }
            }
        }
    }
}
