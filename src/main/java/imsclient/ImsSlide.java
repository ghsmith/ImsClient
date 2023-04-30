package imsclient;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 *
 * @author ghs
 */
public class ImsSlide implements Comparable<ImsSlide> {
    
    static final SimpleDateFormat fZulu = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    static { fZulu.setTimeZone(java.util.TimeZone.getTimeZone("Zulu")); }
    static Pattern pSlideId = Pattern.compile("^(.*)-(.*)-([A-Z])(.*)-(.*)$");
    static Pattern pStain = Pattern.compile("^([0-9]+)-(.*)$");
    
    String labSlideIdString;
    String staining;
    Boolean hasImage;
    Boolean isViewable;
    String id;
    String scanDateTime;
    String parsedPart;
    Integer parsedBlock;
    Integer parsedSlide;
    String parsedStain;
    Date parsedScanTime;

    public ImsSlide(JSONObject slide) throws ParseException {
        labSlideIdString = slide.getString("labSlideIdString");
        staining = slide.getString("staining");
        hasImage = slide.getBoolean("hasImage");
        isViewable = slide.get("isViewable").equals(org.json.JSONObject.NULL) ? null : slide.getBoolean("isViewable");
        Matcher mSlideId = pSlideId.matcher(labSlideIdString);
        mSlideId.matches();
        parsedPart = mSlideId.group(3);
        parsedBlock = Integer.valueOf(mSlideId.group(4));
        parsedSlide = Integer.valueOf(mSlideId.group(5));
        Matcher mStain = pStain.matcher(staining);
        mStain.matches();
        parsedStain = mStain.group(2);
        if(hasImage) {
            id = slide.getString("id");
            scanDateTime = slide.getString("scanDateTime");
            parsedScanTime = fZulu.parse(scanDateTime);
        }
    }

    @Override
    public int compareTo(ImsSlide o) {
        if(parsedPart.compareTo(o.parsedPart) != 0) {
            return parsedPart.compareTo(o.parsedPart);
        }
        if(parsedBlock.compareTo(o.parsedBlock) != 0) {
            return parsedBlock.compareTo(o.parsedBlock);
        }
        return parsedSlide.compareTo(o.parsedSlide);
    }

}