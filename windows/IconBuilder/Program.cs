// 将 Win10 的 PNG 帧 ICO 转换为 Win7 兼容的 32bpp BMP 帧 ICO
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;

namespace Scroff.IconBuilder
{
    class Program
    {
        static void Main()
        {
            var sourcePath = Path.Combine("..", "Scroff", "Assets", "scroff.ico");
            var outPath = Path.Combine("..", "win7", "Scroff.ico");

            var frames = ReadPngFramesFromIco(sourcePath)
                .OrderBy(f => f.Width)
                .ToList();

            if (frames.Count == 0)
                throw new InvalidOperationException("No frames found in source ICO: " + Path.GetFullPath(sourcePath));

            WriteBmpIcon(frames, outPath);
            Console.WriteLine($"ICO written: {Path.GetFullPath(outPath)} ({frames.Count} frames)");
        }

        /// <summary>
        /// 读取 ICO 中的每一帧（预期为 PNG 压缩），返回原始尺寸的 Bitmap。
        /// </summary>
        static System.Collections.Generic.List<Bitmap> ReadPngFramesFromIco(string path)
        {
            var frames = new System.Collections.Generic.List<Bitmap>();
            using (var fs = File.OpenRead(path))
            using (var br = new BinaryReader(fs))
            {
                br.ReadUInt16();              // Reserved
                br.ReadUInt16();              // Type (1 = icon)
                int count = br.ReadUInt16();  // Image count

                var entries = new (int offset, int size)[count];
                for (int i = 0; i < count; i++)
                {
                    br.ReadByte();            // Width
                    br.ReadByte();            // Height
                    br.ReadByte();            // Colors
                    br.ReadByte();            // Reserved
                    br.ReadUInt16();          // Planes
                    br.ReadUInt16();          // Bit count
                    int size = br.ReadInt32();
                    int offset = br.ReadInt32();
                    entries[i] = (offset, size);
                }

                foreach (var entry in entries)
                {
                    fs.Position = entry.offset;
                    byte[] pngData = br.ReadBytes(entry.size);
                    using (var ms = new MemoryStream(pngData))
                    {
                        frames.Add(new Bitmap(ms));
                    }
                }
            }
            return frames;
        }

        /// <summary>
        /// 将 Bitmap 帧写入为纯 BMP 帧的 ICO（32bpp ARGB），兼容 Windows 7 资源编译器。
        /// </summary>
        static void WriteBmpIcon(System.Collections.Generic.List<Bitmap> frames, string outPath)
        {
            var dibs = frames.Select(f => Build32bppDib(f)).ToList();

            using (var fs = File.Create(outPath))
            using (var bw = new BinaryWriter(fs))
            {
                // ICONDIR
                bw.Write((ushort)0);          // Reserved
                bw.Write((ushort)1);          // Type = icon
                bw.Write((ushort)frames.Count);

                // ICONDIRENTRY
                long offset = 6 + 16L * frames.Count;
                for (int i = 0; i < frames.Count; i++)
                {
                    int w = frames[i].Width;
                    int h = frames[i].Height;
                    bw.Write((byte)(w >= 256 ? 0 : w));
                    bw.Write((byte)(h >= 256 ? 0 : h));
                    bw.Write((byte)0);        // Colors
                    bw.Write((byte)0);        // Reserved
                    bw.Write((ushort)1);      // Planes
                    bw.Write((ushort)32);     // Bits per pixel (ARGB)
                    bw.Write((uint)dibs[i].Length);
                    bw.Write((uint)offset);
                    offset += dibs[i].Length;
                }

                // DIB data (BITMAPINFOHEADER + XOR + AND)
                foreach (var dib in dibs)
                    bw.Write(dib);
            }
        }

        /// <summary>
        /// 构建一个 32bpp ARGB 的 DIB 数据块，包含 BITMAPINFOHEADER、XOR 位图和 AND mask。
        /// </summary>
        static byte[] Build32bppDib(Bitmap src)
        {
            // 统一转换为 32bpp ARGB，保留透明通道
            using (var bmp = new Bitmap(src.Width, src.Height, PixelFormat.Format32bppArgb))
            {
                bmp.SetResolution(src.HorizontalResolution, src.VerticalResolution);
                using (var g = Graphics.FromImage(bmp))
                {
                    g.Clear(Color.Transparent);
                    // 使用高质量插值，避免缩放时产生锯齿
                    g.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.HighQualityBicubic;
                    g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
                    g.PixelOffsetMode = System.Drawing.Drawing2D.PixelOffsetMode.HighQuality;
                    g.DrawImage(src, 0, 0, bmp.Width, bmp.Height);
                }

                int w = bmp.Width;
                int h = bmp.Height;
                int maskRowSize = ((w + 31) / 32) * 4;
                int maskBytes = maskRowSize * h;

                var data = bmp.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
                try
                {
                    int xorBytes = data.Stride * h;
                    byte[] scan = new byte[xorBytes];
                    Marshal.Copy(data.Scan0, scan, 0, xorBytes);

                    using (var ms = new MemoryStream())
                    using (var bw = new BinaryWriter(ms))
                    {
                        // BITMAPINFOHEADER (40 bytes)
                        bw.Write((uint)40);                        // biSize
                        bw.Write((int)w);                          // biWidth
                        bw.Write((int)(h * 2));                    // biHeight = XOR + AND
                        bw.Write((ushort)1);                       // biPlanes
                        bw.Write((ushort)32);                      // biBitCount
                        bw.Write((uint)0);                         // biCompression = BI_RGB
                        bw.Write((uint)(xorBytes + maskBytes));    // biSizeImage
                        bw.Write((int)0);                          // biXPelsPerMeter
                        bw.Write((int)0);                          // biYPelsPerMeter
                        bw.Write((uint)0);                         // biClrUsed
                        bw.Write((uint)0);                         // biClrImportant

                        // XOR data: bottom-up, already 32bpp BGRA
                        for (int y = h - 1; y >= 0; y--)
                            bw.Write(scan, y * data.Stride, data.Stride);

                        // AND mask: 1bpp，全 0 表示不透明（使用 alpha 通道处理透明度）
                        for (int y = 0; y < h; y++)
                        {
                            int rowBytes = (w + 7) / 8;
                            for (int b = 0; b < rowBytes; b++) bw.Write((byte)0);
                            int pad = maskRowSize - rowBytes;
                            for (int p = 0; p < pad; p++) bw.Write((byte)0);
                        }

                        return ms.ToArray();
                    }
                }
                finally
                {
                    bmp.UnlockBits(data);
                }
            }
        }
    }
}
