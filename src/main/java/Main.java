// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import edu.wpi.cscore.MjpegServer;
import edu.wpi.cscore.UsbCamera;
import edu.wpi.cscore.VideoSource;
import edu.wpi.cscore.CvSource;
import edu.wpi.first.cameraserver.CameraServer;
import edu.wpi.first.networktables.EntryListenerFlags;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.vision.VisionPipeline;
import edu.wpi.first.vision.VisionThread;

import org.opencv.core.Mat;
import org.opencv.core.Core;
import org.opencv.core.Point;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

//	System.loadLibrary( Core.NATIVE_LIBRARY_NAME );

/*
   JSON format:
   {
       "team": <team number>,
       "ntmode": <"client" or "server", "client" if unspecified>
       "cameras": [
           {
               "name": <camera name>
               "path": <path, e.g. "/dev/video0">
               "pixel format": <"MJPEG", "YUYV", etc>   // optional
               "width": <video mode width>              // optional
               "height": <video mode height>            // optional
               "fps": <video mode fps>                  // optional
               "brightness": <percentage brightness>    // optional
               "white balance": <"auto", "hold", value> // optional
               "exposure": <"auto", "hold", value>      // optional
               "properties": [                          // optional
                   {
                       "name": <property name>
                       "value": <property value>
                   }
               ],
               "stream": {                              // optional
                   "properties": [
                       {
                           "name": <stream property name>
                           "value": <stream property value>
                       }
                   ]
               }
           }
       ]
       "switched cameras": [
           {
               "name": <virtual camera name>
               "key": <network table key used for selection>
               // if NT value is a string, it's treated as a name
               // if NT value is a double, it's treated as an integer index
           }
       ]
   }
 */

public final class Main {
  private static String configFile = "/boot/frc.json";

  @SuppressWarnings("MemberName")
  public static class CameraConfig {
    public String name;
    public String path;
    public JsonObject config;
    public JsonElement streamConfig;
  }

  @SuppressWarnings("MemberName")
  public static class SwitchedCameraConfig {
    public String name;
    public String key;
  };

  public static int team;
  public static boolean server;
  public static List<CameraConfig> cameraConfigs = new ArrayList<>();
  public static List<SwitchedCameraConfig> switchedCameraConfigs = new ArrayList<>();
  public static List<VideoSource> cameras = new ArrayList<>();

  private Main() {
  }

  /**
   * Report parse error.
   */
  public static void parseError(String str) {
    System.err.println("config error in '" + configFile + "': " + str);
  }

  /**
   * Read single camera configuration.
   */
  public static boolean readCameraConfig(JsonObject config) {
    CameraConfig cam = new CameraConfig();

    // name
    JsonElement nameElement = config.get("name");
    if (nameElement == null) {
      parseError("could not read camera name");
      return false;
    }
    cam.name = nameElement.getAsString();

    // path
    JsonElement pathElement = config.get("path");
    if (pathElement == null) {
      parseError("camera '" + cam.name + "': could not read path");
      return false;
    }
    cam.path = pathElement.getAsString();

    // stream properties
    cam.streamConfig = config.get("stream");

    cam.config = config;

    cameraConfigs.add(cam);
    return true;
  }

  /**
   * Read single switched camera configuration.
   */
  public static boolean readSwitchedCameraConfig(JsonObject config) {
    SwitchedCameraConfig cam = new SwitchedCameraConfig();

    // name
    JsonElement nameElement = config.get("name");
    if (nameElement == null) {
      parseError("could not read switched camera name");
      return false;
    }
    cam.name = nameElement.getAsString();

    // path
    JsonElement keyElement = config.get("key");
    if (keyElement == null) {
      parseError("switched camera '" + cam.name + "': could not read key");
      return false;
    }
    cam.key = keyElement.getAsString();

    switchedCameraConfigs.add(cam);
    return true;
  }

  /**
   * Read configuration file.
   */
  @SuppressWarnings("PMD.CyclomaticComplexity")
  public static boolean readConfig() {
    // parse file
    JsonElement top;
    try {
      top = new JsonParser().parse(Files.newBufferedReader(Paths.get(configFile)));
    } catch (IOException ex) {
      System.err.println("could not open '" + configFile + "': " + ex);
      return false;
    }

    // top level must be an object
    if (!top.isJsonObject()) {
      parseError("must be JSON object");
      return false;
    }
    JsonObject obj = top.getAsJsonObject();

    // team number
    JsonElement teamElement = obj.get("team");
    if (teamElement == null) {
      parseError("could not read team number");
      return false;
    }
    team = teamElement.getAsInt();

    // ntmode (optional)
    if (obj.has("ntmode")) {
      String str = obj.get("ntmode").getAsString();
      if ("client".equalsIgnoreCase(str)) {
        server = false;
      } else if ("server".equalsIgnoreCase(str)) {
        server = true;
      } else {
        parseError("could not understand ntmode value '" + str + "'");
      }
    }

    // cameras
    JsonElement camerasElement = obj.get("cameras");
    if (camerasElement == null) {
      parseError("could not read cameras");
      return false;
    }
    JsonArray cameras = camerasElement.getAsJsonArray();
    for (JsonElement camera : cameras) {
      if (!readCameraConfig(camera.getAsJsonObject())) {
        return false;
      }
    }

    if (obj.has("switched cameras")) {
      JsonArray switchedCameras = obj.get("switched cameras").getAsJsonArray();
      for (JsonElement camera : switchedCameras) {
        if (!readSwitchedCameraConfig(camera.getAsJsonObject())) {
          return false;
        }
      }
    }

    return true;
  }

  /**
   * Start running the camera.
   */
  public static VideoSource startCamera(CameraConfig config) {
    System.out.println("Starting camera '" + config.name + "' on " + config.path);
    CameraServer inst = CameraServer.getInstance();
    UsbCamera camera = new UsbCamera(config.name, config.path);
    MjpegServer server = inst.startAutomaticCapture(camera);
 
    Gson gson = new GsonBuilder().create();

    camera.setConfigJson(gson.toJson(config.config));
    camera.setConnectionStrategy(VideoSource.ConnectionStrategy.kKeepOpen);

    if (config.streamConfig != null) {
      server.setConfigJson(gson.toJson(config.streamConfig));
     }
 
     return camera;
   }
 
   /**
    * Start running the switched camera.
    */
  public static MjpegServer startSwitchedCamera(SwitchedCameraConfig config) {
    System.out.println("Starting switched camera '" + config.name + "' on " + config.key);
    MjpegServer server = CameraServer.getInstance().addSwitchedCamera(config.name);

    NetworkTableInstance.getDefault()
        .getEntry(config.key)
        .addListener(event -> {
              if (event.value.isDouble()) {
                int i = (int) event.value.getDouble();
                if (i >= 0 && i < cameras.size()) {
                  server.setSource(cameras.get(i));
                }
              } else if (event.value.isString()) {
                String str = event.value.getString();
                for (int i = 0; i < cameraConfigs.size(); i++) {
                  if (str.equals(cameraConfigs.get(i).name)) {
                    server.setSource(cameras.get(i));
                    break;
                  }
                }
              }
            },
            EntryListenerFlags.kImmediate | EntryListenerFlags.kNew | EntryListenerFlags.kUpdate);

    return server;
  }

  /**
   * Example pipeline.
   */
  public static class MyPipeline implements VisionPipeline {
    public int val;
    public double tx, ty, ta;
    public Mat output_image;
    public Mat work_image;

    // old wiffle ball
    //static void Scalar threshold_low = new Scalar(18, 70, 30);
    //static void Scalar threshold_high = new Scalar(40, 255, 255);

    // wiffle
    //static Scalar threshold_low = new Scalar(30, 20, 80);
    //static Scalar threshold_high = new Scalar(50, 255, 255);
    static Scalar threshold_low = new Scalar(30, 80, 80);
    static Scalar threshold_high = new Scalar(55, 255, 255);

    static Scalar color_red = new Scalar(0, 0, 255);
    static Size blur_kernel =  new Size(13, 13);

    @Override
    public void process(Mat mat) {
	output_image = mat.clone();
	work_image = new Mat(mat.rows(), mat.cols(), mat.type());
	Imgproc.GaussianBlur(mat, work_image, blur_kernel, 0);
	Imgproc.cvtColor(work_image, work_image, Imgproc.COLOR_BGR2HSV);
	Core.inRange(work_image, threshold_low, threshold_high, work_image);

        List<MatOfPoint> contours = new ArrayList<>();
	Mat hierarchy = new Mat();
	Imgproc.findContours(work_image, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE);
	val = 0;
	double max_area = 0.0;
	int max_x = 0;
	int max_y = 0;
	int max_w = 0;
	int max_h = 0;
	
	for (int i = 0; i < contours.size(); i++) {
	    Rect rect = Imgproc.boundingRect(contours.get(i));
	    if (rect.area() < 60) {
		continue;
	    }
	    int w = rect.width;
	    int h = rect.height;
	    float aspect = w / h;
	    if (aspect < 0.9 || aspect > 1.1) {
		continue;
	    }
	    if (rect.area() > max_area) {
		max_x = rect.x;
		max_y = rect.y;
		max_w = w;
		max_h = h;
		max_area = rect.area();
	    }
	    // area = Imgproc.contourArea(m);
	    // solidity = area / (width * height);

	    // centerX = x + width / 2;
	    // centerY = y + height / 2;                    
	    
	    // Imgproc.drawContours(output_image, contours, i, color_red, 2, Core.LINE_8, hierarchy, 0, new Point());
	    //Imgproc.rectangle(output_image, new Point(x, y), new Point(x+w, y+h), color_red, 2);
	    val += 1;
	}
	if (max_area > 0) {
	    tx = (max_x + max_w / 2) - mat.cols() / 2;
	    ty = (max_y + max_h / 2) - mat.rows() / 2;
	    ta = max_area;
	    //tw = max_w;
	    //th = max_h;
	    Imgproc.rectangle(output_image, new Point(max_x, max_y), new Point(max_x+max_w, max_y+max_h), color_red, 2);
	}
	//Imgproc.putText(output_image, Integer.toString(val), new Point(80, 100), Core.FONT_HERSHEY_SIMPLEX, 1.0,
	//		new Scalar(0, 255, 255), 1, Imgproc.LINE_AA, false);
	//val += 1;
    }
  }

  /**
   * Main.
   */
  public static void main(String... args) {
    if (args.length > 0) {
      configFile = args[0];
    }

    // read configuration
    if (!readConfig()) {
      return;
    }

    // start NetworkTables
    NetworkTableInstance ntinst = NetworkTableInstance.getDefault();
    if (server) {
      System.out.println("Setting up NetworkTables server");
      ntinst.startServer();
    } else {
      System.out.println("Setting up NetworkTables client for team " + team);
      ntinst.startClientTeam(team);
      ntinst.startDSClient();
    }

    // start cameras
    for (CameraConfig config : cameraConfigs) {
      cameras.add(startCamera(config));
    }

    // start switched cameras
    for (SwitchedCameraConfig config : switchedCameraConfigs) {
      startSwitchedCamera(config);
    }

    // start image processing on camera 0 if present
    if (cameras.size() >= 1) {
	CvSource output_stream = CameraServer.getInstance().putVideo("Processed", 320, 240);
	VisionThread visionThread = new VisionThread(cameras.get(0),
              new MyPipeline(), pipeline -> {
		 ntinst.getTable("datatable").getEntry("val").setNumber(pipeline.val);
		 ntinst.getTable("datatable").getEntry("tx").setNumber(pipeline.tx);
		 ntinst.getTable("datatable").getEntry("ty").setNumber(pipeline.ty);
		 ntinst.getTable("datatable").getEntry("ta").setNumber(pipeline.ta);
		 output_stream.putFrame(pipeline.output_image);
        // do something with pipeline results
      });
      /* something like this for GRIP:
      VisionThread visionThread = new VisionThread(cameras.get(0),
              new GripPipeline(), pipeline -> {
        ...
      });
       */
      visionThread.start();
    }

    // loop forever
    for (;;) {
      try {
        Thread.sleep(10000);
      } catch (InterruptedException ex) {
        return;
      }
    }
  }
}
