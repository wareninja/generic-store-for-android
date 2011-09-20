/* Copyright (c) 2009 Matthias Kaeppler
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wareninja.opensource.droidfu.imageloader;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import javax.net.ssl.HttpsURLConnection;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Message;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.widget.ImageView;

import com.wareninja.opensource.common.HttpUtils;
import com.wareninja.opensource.common.LOGGING;
import com.wareninja.opensource.droidfu.cachefu.ImageCache;
import com.wareninja.opensource.droidfu.widgets.WebImageView;


/**
 * Realizes an background image loader backed by a two-level FIFO cache. If the image to be loaded
 * is present in the cache, it is set immediately on the given view. Otherwise, a thread from a
 * thread pool will be used to download the image in the background and set the image on the view as
 * soon as it completes.
 * 
 * @author Matthias Kaeppler
 */
public class ImageLoader implements Runnable {

    public static final int HANDLER_MESSAGE_ID = 0;
    public static final String BITMAP_EXTRA = "droidfu:extra_bitmap";
    public static final String IMAGE_URL_EXTRA = "droidfu:extra_image_url";

    private static final String LOG_TAG = "Droid-Fu/ImageLoader";
    // the default thread pool size
    private static final int DEFAULT_POOL_SIZE = 3;
    // expire images after a day
    // TODO: this currently only affects the in-memory cache, so it's quite pointless
    private static final int DEFAULT_TTL_MINUTES = 60;//24 * 60;
    private static final int DEFAULT_RETRY_HANDLER_SLEEP_TIME = 1000;
    private static final int DEFAULT_NUM_RETRIES = 3;

    private static ThreadPoolExecutor executor;
    private static ImageCache imageCache;
    private static int numRetries = DEFAULT_NUM_RETRIES;

    /**
     * @param numThreads
     *            the maximum number of threads that will be started to download images in parallel
     */
    public static void setThreadPoolSize(int numThreads) {
        executor.setMaximumPoolSize(numThreads);
    }

    /**
     * @param numAttempts
     *            how often the image loader should retry the image download if network connection
     *            fails
     */
    public static void setMaxDownloadAttempts(int numAttempts) {
        ImageLoader.numRetries = numAttempts;
    }

    /**
     * This method must be called before any other method is invoked on this class. Please note that
     * when using ImageLoader as part of {@link WebImageView} or {@link WebGalleryAdapterOLD}, then
     * there is no need to call this method, since those classes will already do that for you. This
     * method is idempotent. You may call it multiple times without any side effects.
     * 
     * @param context
     *            the current context
     */
    public static synchronized void initialize(Context context) {
        if (executor == null) {
            executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(DEFAULT_POOL_SIZE);
        }
        if (imageCache == null) {
            imageCache = new ImageCache(25, DEFAULT_TTL_MINUTES, DEFAULT_POOL_SIZE);
            imageCache.enableDiskCache(context, ImageCache.DISK_CACHE_SDCARD);
        }
    }
    //YG: create cache before in Application and pass here
    public static synchronized void initializeWithPreInit(ImageCache preInitImageCache) {
        if (executor == null) {
            executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(DEFAULT_POOL_SIZE);
        }
        //if (imageCache == null) {
            imageCache = preInitImageCache;
        //}
    }
    
    // YG: to set custom user-agent header
    static String customHttpUserAgent = "";
    public static synchronized void setHttpUserAgent(String httpUserAgent) {
		customHttpUserAgent = httpUserAgent;
	}
    
    
    private String imageUrl;
    private String imageUrlCached;
    private ImageLoaderHandler handler;

    private ImageLoader(String imageUrl, String imageUrlCached, ImageLoaderHandler handler) {
        this.imageUrl = imageUrl;
        this.handler = handler;
        this.imageUrlCached = imageUrlCached;
    }

    /**
     * Triggers the image loader for the given image and view. The image loading will be performed
     * concurrently to the UI main thread, using a fixed size thread pool. The loaded image will be
     * posted back to the given ImageView upon completion.
     * 
     * @param imageUrl
     *            the URL of the image to download
     * @param imageView
     *            the ImageView which should be updated with the new image
     */
    public static void start(String imageUrl, String imageUrlCached, ImageView imageView) {
        start(imageUrl, imageUrlCached, imageView, new ImageLoaderHandler(imageView, imageUrl, imageUrlCached));
    }

    /**
     * Triggers the image loader for the given image and handler. The image loading will be
     * performed concurrently to the UI main thread, using a fixed size thread pool. The loaded
     * image will not be automatically posted to an ImageView; instead, you can pass a custom
     * {@link ImageLoaderHandler} and handle the loaded image yourself (e.g. cache it for later
     * use).
     * 
     * @param imageUrl
     *            the URL of the image to download
     * @param handler
     *            the handler which is used to handle the downloaded image
     */
    public static void start(String imageUrl, String imageUrlCached, ImageLoaderHandler handler) {
        start(imageUrl, imageUrlCached, handler.getImageView(), handler);
    }

    private static void start(String imageUrl, String imageUrlCached, ImageView imageView, ImageLoaderHandler handler) {
        if (imageView != null) {
            String oldImageUrl = (String) imageView.getTag();
            if (imageUrl.equals(oldImageUrl)) {
                // nothing to do
                return;
            } else {
                imageView.setTag(imageUrl);
            }
        }

        if (imageCache.containsKeyInMemory(imageUrlCached)) {
            // do not go through message passing, handle directly instead
            handler.handleImageLoaded(imageCache.getBitmap(imageUrlCached), null);
        } else {
            executor.execute(new ImageLoader(imageUrl, imageUrlCached, handler));
        }
    }

    /**
     * Clears the 1st-level cache (in-memory cache). A good candidate for calling in
     * {@link android.app.Application#onLowMemory()}.
     */
    public static void clearCache() {
        imageCache.clear();
    }

    /**
     * Returns the image cache backing this image loader.
     * 
     * @return the {@link ImageCache}
     */
    public static ImageCache getImageCache() {
        return imageCache;
    }

    /**
     * The job method run on a worker thread. It will first query the image cache, and on a miss,
     * download the image from the Web.
     */
    public void run() {
        // TODO: if we had a way to check for in-memory hits, we could improve performance by
        // fetching an image from the in-memory cache on the main thread
        Bitmap bitmap = imageCache.getBitmap(imageUrlCached);

        if (bitmap == null) {
            bitmap = downloadImage();
        }

        if (bitmap != null) {
            notifyImageLoaded(imageUrl, bitmap);
        } // TODO: notify about failure otherwise?
    }

    // TODO: we could probably improve performance by re-using connections instead of closing them
    // after each and every download
    protected Bitmap downloadImage() {
        int timesTried = 1;

        while (timesTried <= numRetries) {
            try {
            	byte[] imageData = retrieveImageData();

                imageCache.put(imageUrlCached, imageData);
                return BitmapFactory.decodeByteArray(imageData, 0, imageData.length);

            } catch (Throwable e) {
                Log.w(LOG_TAG, "download for " + imageUrl + " saved as " + imageUrlCached + " failed (attempt " + timesTried + ")");
                e.printStackTrace();
                SystemClock.sleep(DEFAULT_RETRY_HANDLER_SLEEP_TIME);
                timesTried++;
            }
        }

        return null;
    }
    // customized by YG@WareNinja: to support both http and https urls
    protected byte[] retrieveImageData() throws IOException
    	, NoSuchAlgorithmException, KeyManagementException {
    	
        URL url = new URL(imageUrl);
        
        // this supports ONLY http
        //-HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        
        URLConnection connection = HttpUtils.getConnection(url);
        
        if (!TextUtils.isEmpty(customHttpUserAgent)) {
        	connection.setRequestProperty( "User-Agent", customHttpUserAgent );
        }
        // determine the image size and allocate a buffer
        int fileSize = connection.getContentLength();
        byte[] imageData = new byte[fileSize];

        // download the file
        if (LOGGING.DEBUG)Log.d(LOG_TAG, "-----------fetching image " + imageUrl + " (" + fileSize + ")");
        //BufferedInputStream istream = new BufferedInputStream(connection.getInputStream());
        BufferedInputStream istream = new BufferedInputStream(connection.getInputStream(), 10240);//YG: 10k=10240
        int bytesRead = 0;
        int offset = 0;
        while (bytesRead != -1 && offset < fileSize) {
            bytesRead = istream.read(imageData, offset, fileSize - offset);
            offset += bytesRead;
        }

        // clean up
        istream.close();
        //-connection.disconnect();
        
        // add by YG
        if (connection instanceof HttpsURLConnection)
        	((HttpsURLConnection)connection).disconnect();
        else
        	((HttpURLConnection)connection).disconnect();
        

        return imageData;
    }

    public void notifyImageLoaded(String url, Bitmap bitmap) {
        Message message = new Message();
        message.what = HANDLER_MESSAGE_ID;
        Bundle data = new Bundle();
        data.putString(IMAGE_URL_EXTRA, url);
        data.putParcelable(BITMAP_EXTRA, bitmap);
        message.setData(data);

        handler.sendMessage(message);
    }
}
