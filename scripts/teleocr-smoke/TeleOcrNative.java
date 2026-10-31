package app.readflow.ingest;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
public class TeleOcrNative {
 native long nativeCreate(int threads);
 native void nativeLoad(long handle,String model,String projector,int imageTokens);
 native byte[] nativeRecognize(long h,byte[] rgb,int w,int hgt,String prompt,int maxTokens);
 native void nativeClose(long h);
 native void nativeCancel(long h);
 public static void main(String[] args) throws Exception {
  System.load(new File(".deps/teleocr-jni-host/libreadflow_teleocr.so").getAbsolutePath());
  TeleOcrNative n=new TeleOcrNative();
  long start=System.nanoTime();
  long h=n.nativeCreate(2);
  try {
   Thread loadCancel = null;
   if (args.length>2 && args[2].equals("load-cancellation")) {
    loadCancel = new Thread(() -> { try { Thread.sleep(50); } catch (InterruptedException e) {} n.nativeCancel(h); });
    loadCancel.start();
   }
   try {
    n.nativeLoad(h,".deps/teleocr-model/NaviDC-OCR-Q5_K_M.gguf",".deps/teleocr-model/NaviDC-OCR-mmproj-q8_0.gguf",1369);
    if (loadCancel != null) throw new AssertionError("Model loading ignored cancellation");
   } catch (IllegalStateException e) {
    if (loadCancel == null || !e.getMessage().contains("cancelled")) throw e;
    loadCancel.join();
    System.out.println("PASS: cancellation interrupts model loading");
    return;
   }
   BufferedImage image=ImageIO.read(new File(args[0]));
   byte[] rgb=new byte[image.getWidth()*image.getHeight()*3];
   for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) {
    int c=image.getRGB(x,y), i=(y*image.getWidth()+x)*3;
    rgb[i]=(byte)(c>>16); rgb[i+1]=(byte)(c>>8); rgb[i+2]=(byte)c;
   }
   String prompt=args.length>1?args[1]:"Please output the text content from the image.";
   if (args.length>2 && args[2].equals("failures")) {
    try {
     n.nativeRecognize(h,rgb,image.getWidth(),image.getHeight(),prompt,1);
     throw new AssertionError("Truncated result was accepted");
    } catch (IllegalStateException e) {
     if (!e.getMessage().contains("truncated")) throw e;
     System.out.println("PASS: token limit rejects incomplete text");
    }
    Thread cancel = new Thread(() -> { try { Thread.sleep(200); } catch (InterruptedException e) {} n.nativeCancel(h); });
    cancel.start();
    try {
     n.nativeRecognize(h,rgb,image.getWidth(),image.getHeight(),prompt,512);
     throw new AssertionError("Cancelled result was accepted");
    } catch (IllegalStateException e) {
     if (!e.getMessage().contains("cancelled")) throw e;
     System.out.println("PASS: cancellation interrupts inference");
    }
    cancel.join();
    return;
   }
   System.out.println(new String(n.nativeRecognize(h,rgb,image.getWidth(),image.getHeight(),prompt,512),StandardCharsets.UTF_8));
   System.out.println("Elapsed seconds: "+(System.nanoTime()-start)/1e9);
  } finally { n.nativeClose(h); }
 }
}
