package imsclient;

import java.io.*;
import java.net.URLEncoder;
import java.text.ParseException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import org.apache.commons.io.IOUtils;
import org.glassfish.jersey.apache.connector.ApacheConnectorProvider;
import org.glassfish.jersey.client.ClientConfig;
import org.json.JSONException;
import org.json.JSONObject;

/**
 *
 * @author ghs
 */
public class HtmlSlideTray {

    public static void main(String[] args) throws IOException, ParseException {

        String url = args[0];
        String u = args[1];
        String p = args[2];
        Set<String> searchSet = new HashSet<>();
        {
            for(int x = 3; x < args.length; x++) {
                searchSet.add(args[x]);
            }
        }

        ClientConfig cc = new ClientConfig().connectorProvider(new ApacheConnectorProvider());
        Client client = ClientBuilder.newClient(cc);
        Logger.getLogger("org.apache.http.client.protocol").setLevel(Level.SEVERE);
        
        // 1. Login.
        {
            String response = client
                .target(url + "/uniview/Logon.ashx")
                .request(MediaType.APPLICATION_JSON)
                .post(
                    Entity.entity(String.format("{\"Domain\":\"\",\"Password\":\"%s\",\"UserName\":\"%s\"}", p, u), MediaType.APPLICATION_JSON),
                    String.class
                );
        }

        // loop through all accession numbers passed in on the command-line
        for(String search : searchSet) {
            
            String uniViewPatientIdentifier = null;
            String patientHistoryFetchToken = null;
            String uniViewHistoryItemIdentifier = null;
            String fetchToken = null;
            String value = null;
            String hash = null;

            // 2. Get a fetch token for the patient history, using the accession
            //    number passed in on the command-line.
            try {
                String response = client
                    .target(url + "/uniview/Search.ashx")
                    .request(MediaType.APPLICATION_JSON)
                    .post(
                        Entity.entity(String.format("searchServer=2&freeText=%s", search), MediaType.APPLICATION_FORM_URLENCODED),
                        String.class
                    );
                JSONObject jo = new JSONObject(response);
                uniViewPatientIdentifier = jo.getJSONArray("Patients").getJSONObject(0).getString("UniViewPatientIdentifier");
                patientHistoryFetchToken = jo.getJSONArray("Patients").getJSONObject(0).getString("PatientHistoryFetchToken");
            }
            catch(JSONException e) {
                System.out.println();
                System.out.println(search + " not found");
                continue;
            }

            // 3. In the patient history, find the fetch token for the pathology
            //    case.
            {
                String response = client
                    .target(url + "/uniview/GetPatientHistory.ashx")
                    .request(MediaType.APPLICATION_JSON)
                    .post(
                        Entity.entity("patientHistoryFetchToken=" + URLEncoder.encode(patientHistoryFetchToken), MediaType.APPLICATION_FORM_URLENCODED),
                        String.class
                    );
                JSONObject jo = new JSONObject(response);
                for (Iterator iter = jo.getJSONArray("History").iterator(); iter.hasNext(); ) {
                    JSONObject history = (JSONObject)iter.next();
                    String accessionNumber = history.getString("AccessionNumber");
                    if(accessionNumber.equals(search)) {
                        uniViewHistoryItemIdentifier = history.getString("UniViewHistoryItemIdentifier");
                        fetchToken = history.getJSONArray("RequestedProcedures").getJSONObject(0).getString("FetchToken");
                    }
                }
            }

            // 4. Initialize a pathology session for the pathology case.
            {
                String response = client
                    .target(url + "/uniview/InitializePathologySession.ashx")
                    .request(MediaType.APPLICATION_JSON)
                    .post(
                        Entity.entity("fetchToken=" + URLEncoder.encode(fetchToken), MediaType.APPLICATION_FORM_URLENCODED),
                        String.class
                    );
                JSONObject jo = new JSONObject(response);
                value = jo.getString("Value");
                hash = jo.getString("Hash");
            }

            // 5. Create a virtual slide tray with the slide label and macro
            //    images. To make this a stand-alone file, the image content is
            //    base-64 encoded into the HTML document:
            //    <img src="data:image/jpeg;base64,{base-64-JPEG}"/>
            {
                Date now = new Date();
                System.out.println();
                System.out.println(value);
                // 5a. Get the JSON slide array for the case and load it into an
                //     ImsSlide array.
                String response = client
                    .target(String.format(url + "/SectraPathologyServer/api/requestslides?requestId=%s&hash=%s", URLEncoder.encode(value), URLEncoder.encode(hash)))
                    .request(MediaType.APPLICATION_JSON)
                    .get(
                        String.class
                    );
                JSONObject jo = new JSONObject(response);
                List<ImsSlide> slideList = new ArrayList<>();
                int x = 0;
                for (Iterator iter = jo.getJSONArray("slides").iterator(); iter.hasNext(); ) {
                    x++;
                    JSONObject slideJson = (JSONObject)iter.next();
                    ImsSlide slide = new ImsSlide(slideJson);
                    System.out.println(String.format("[%2d] %s (%s): hasImage = %s; isViewable = %s", x, slide.labSlideIdString, slide.staining, slide.hasImage, slide.isViewable));
                    slideList.add(slide);
                }
                Collections.sort(slideList);
                PrintStream outFile = new PrintStream(new File(value + ".html"));
                outFile.println(String.format("<html><head><style>table {border-spacing: 0px; border-collapse: collapse;} th, td {border: 1px solid black;}</style></head><body><h1>%s</h1><h2>slides in IMS as of <u>%s</u></h2><table>", value, now));
                outFile.println(String.format("<tr><th>%s</th><th>%s</th><th>%s</th><th>%s</th><th>%s</th><th>%s</th><th>%s</th></tr>", "block", "no.", "stain", "scan time", "age (days)", "label", "macro"));
                for(ImsSlide slide : slideList) {
                    outFile.println("<tr>");
                    outFile.println(String.format("<td>%s</td><td>%s</td><td>%s</td>", slide.parsedPart + slide.parsedBlock, slide.parsedSlide, slide.parsedStain));
                    if(slide.hasImage) {
                        // 5b. Get the slide label.
                        InputStream responseLabel = client
                            .target(String.format(url + "/SectraPathologyServer/slides/%s/images/label.jpeg", slide.id))
                            .request(MediaType.APPLICATION_OCTET_STREAM)
                            .get(InputStream.class);
                        byte[] responseLabelBytes = Base64.getEncoder().encode(IOUtils.toByteArray(responseLabel));
                        // 5b. Get the slide macro.
                        InputStream macroLabel = client
                            .target(String.format(url + "/SectraPathologyServer/slides/%s/images/slide-photo.jpeg", slide.id))
                            .request(MediaType.APPLICATION_OCTET_STREAM)    
                            .get(InputStream.class);
                        byte[] responseMacroBytes = Base64.getEncoder().encode(IOUtils.toByteArray(macroLabel));
                        long days =
                            (org.apache.commons.lang3.time.DateUtils.truncate(now, Calendar.DATE).getTime()
                            - org.apache.commons.lang3.time.DateUtils.truncate(slide.parsedScanTime, Calendar.DATE).getTime())
                            / (3600000 * 24);
                        outFile.println(String.format("<td>%s</td><td style=\"text-align: right\">%d</td><td><img height=\"150\" src=\"data:image/jpeg;base64,%s\"/></td><td><img height=\"150\" src=\"data:image/jpeg;base64,%s\"/></td>", slide.parsedScanTime, days, new String(responseLabelBytes), new String(responseMacroBytes)));
                    }
                    else {
                        outFile.println(String.format("<td>%s</td><td></td><td><img height=\"150\" src=\"data:image/jpeg;base64,%s\"/></td><td><img height=\"150\" src=\"data:image/jpeg;base64,%s\"/></td>", "pending", hourglassJpeg, hourglassJpeg));
                    }
                    outFile.println("</tr>");
                }
                outFile.println("</table></body></html>");
                outFile.close();
            }

        } // accession number loop

        // 6. Logout.
        {
            String response = client
                .target(url + "/uniview/Logout.ashx")
                .request(MediaType.APPLICATION_JSON)
                .post(
                    Entity.entity("0", MediaType.APPLICATION_FORM_URLENCODED),
                    String.class
                );
        }
        
    }

    static String hourglassJpeg = "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wAARCAEaALIDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD67/aj/bO8G/so33hy18VWOpXkmuRTzW5sEDBREUBzn18wflXhf/D4j4Q/9AbxF/36SvG/+C2JKeIfhIB0FjqYz3/1kAr8yMe1AH7N/wDD4j4Q/wDQG8Rf9+ko/wCHxHwh/wCgN4i/79JX4yY9qMe1AH7N/wDD4j4Q/wDQG8Rf9+ko/wCHxHwh/wCgN4i/79JX4yY9qMe1AH7N/wDD4j4Q/wDQG8Rf9+ko/wCHxHwh/wCgN4i/79JX4yY9qMe1AH7N/wDD4j4Q/wDQG8Rf9+ko/wCHxPwh/wCgN4i/79JX4yY9qMe1AH7O/wDD4z4Rf9AbxF/36T/Gj/h8Z8Iv+gN4i/79J/jX4xY9qMe1AH7N/wDD4j4Q/wDQG8Rf9+ko/wCHxHwh/wCgN4i/79JX4yY9qMe1AH7N/wDD4j4Q/wDQG8Rf9+ko/wCHxHwh/wCgN4i/79JX4yY9qMe1AH7N/wDD4j4Q/wDQG8Rf9+ko/wCHxHwh/wCgN4i/79JX4yY9qMe1AH7N/wDD4j4Q/wDQG8Rf9+ko/wCHxHwh/wCgN4i/79JX4yY9qMe1AH7N/wDD4j4Q/wDQG8Rf9+Ur3j9lz9sfwl+1hL4lXwpY6hZLoItTcNfKF3ef520DHp5B/wC+hX89vTnFfqL/AMERSWm+MxPTGjZHb/l+oA/Ur5v71FG6igD8p/8AgtohPiP4SAck2epY/wC/1vX5lbflJz/nFfpz/wAFtFK658IZmX5GtdUX8pbcn+dei2f/AARd+H95axSt481794gcbbeHABAOP1oA/H/IoyK/YX/hyf8AD3/offEH/gPDR/w5P+Hv/Q++IP8AwHhoA/HrIoyK/YX/AIcn/D3/AKH3xB/4Dw0f8OT/AIe/9D74g/8AAeGgD8esijIr9hf+HJ/w9/6H3xB/4Dw0f8OT/h7/AND74g/8B4aAPx6yKMiv2F/4cn/D3/offEH/AIDw0f8ADk/4e/8AQ++IP/AeGgD8esijIr9hf+HJ/wAPf+h98Qf+A8NH/Dk/4e/9D74g/wDAeGgD8esijIr9hf8Ahyf8Pf8AoffEH/gPDR/w5P8Ah7/0PviD/wAB4aAPx6yKMiv2F/4cn/D3/offEH/gPDR/w5P+Hv8A0PviD/wHhoA/HrIoyK/YX/hyf8Pf+h98Qf8AgPDR/wAOT/h7/wBD74g/8B4aAPx6yKMiv2F/4cn/AA9/6H3xB/4Dw0f8OT/h7/0PviD/AMB4aAPx53V+pH/BEVSs3xlHByNHx+H27NdX4q/4I2/Dvwv4X1jWG8da9Kun2c12UaCIBvLQvgn8K5r/AIIk258n4yXJX5GbR41PqQL0n8cMKAP1C20UtFAH5j/8FtNMa40D4QagB+7t7jVbdm93W2df/RbV+jfwn1pfEvwx8H6tG2+LUNGsrtG9Vkt42B/z618W/wDBYjwmdX/Zi0XVY4yZdH8SW7yN/cikimiJ/F/K/MV9DfsI+Ko/GX7IPwnv423iHQ4dPJzzm2zbHP4wmgD3miiigApN1LSbaAFooooAKKKKACiiigAooooAKKKKACk3UtJtoAWlpKRvunvxQB5V+1R4kXwx+zL8U9TdvJaLwvqPlknpI1s6oPxdlH418Y/8EXNAe3+C/jvWmTaL3xHHag46iG1Dfzmr17/gq548j8H/ALIGvaaH8q68RajaaTDg848z7Q/4bIG/MVf/AOCZfgVvA/7HPgxpYTBd61Jc61KpHLCaVlib8YkjP40AfU9FFFAHg/7cfgFviZ+yT8TtHjTzZ49Kk1KEAZYyWpW5AA9T5O3/AIFXhn/BG/4kDxL+zrrfhWaUNdeGtakWKIH7lvcL5q/+RBcV9zS28VzG8M8aywOhjaNxkMrDDA+xGa/Jr9hq+m/ZN/4KB+NPhHqTNDpmsyT6XbGQ7RIyMZ7J+f78ZKj3kFAH69UVHHOsmNvOeQR0x2qSgAooooAKKKKACiiigAooooAKKKKACiiigAooooAKPwzRXNfETx9pHwz8Ca94r12f7NpGj2cl3cuTg7VUnA9zwo9SQKAPy+/4KyeNL34u/Hj4a/BXw7J595btGZYoySPtt6yJErD1WMBvYS1+nfg3wxZeBfCmieGtMTZYaTZQ6fbr/wBMoY0jGPyz+Br8vP8AgnX4T1f9pj9rTxn8ffE8W+2024lnt/MXKfb512xRrn+GGDgeh8r1r9W1UKQFHyjgD09KAH0UUUAN27uN233HavzC/wCCtHwi1Pwb4u8D/Hnwxutbyxmgsr+4h/5YTxSGW0mP/j0eT/cQd6/T0ncCOn0rkfix8MNG+M3w51/wXr0Hm6XrFq9s+0ZaInlXTP8AErhWHutAFL9nT4zaX8fPg94Y8b6YV26haqbqBD/x7XQG2aI+6uGH0we9em1+Qn7Efxg1f9hv9pLxF8EPiRP9l8P6pfCOO9lYrDBc4xDcrnpFOjKCe2FJ6Gv1r1PxBp2iQpPqV7b6fbu4jE11MsSFjnAyxAycHigDQorHtvGOh3mPs+safOT08q7jb6jg1pw3UVxzDJHKuM5Rwf5UAS0Um6jd7UALRSM2O1G7HY0ALRSbvUYo3CgBaKKM+xoAKKTcKTzMdRigB1DMFUk9BzUNxeRWqb55FhX1kYAVHb6nb3UImglSeI5AkjcMpIOCMj3/AJH0oAsMx2nI7fhX5Yf8FOv2i9S+MHj7Sv2dvh35mpXT30Meri1P+uvSxEdrkfwoSHfsGH+ya+hP+Cgn7cFr+zd4R/4Rnw1cxzfEfVoCIIwQTpsRGGuJB2c5+Re+Ceg588/4Jq/sbT/D+zb4uePrad/HGtq0unW95ky2kEg3PO+efOk5yTyqse7HAB9U/sx/AvTf2cfgvoPgbTyk1xax+dqF0o/4+btmUyvn0BO0f7KKO1erbaao2qFAAH/18/zp26gBaKKKAG0Y3cUUfiR7igD5C/4KEfsaxftL+BYdd8PW0UfxA0KJmtXwF/tCDJZrdj3Ix8voSexr5v8A2e/j1pf7VHwi1j9mf4yXcuj+MYU+z6DrN8SsrTQ/6tHJ586NhtweWXcOtfqYqlsAhecHHbdnk+3FfCn7eX/BP2P4zCb4ifDmMaZ8RLZFuLm3hOxdUKjCsCMbbhccMPvcZ55oA/Lv41/C/wCI/wCzn4+v/C3iKfUtOnhZnguIJ5Fhu4s4WWNgfmB7+h4NcZa/EzxlabjD4s1qIL/d1KYf+zV+g3wn/aX8I/H7QV+B/wC1Lpxs/ENo/wBm03xPdJ5NxFPjaolfGY35A3fdbIz2NeEftVf8E8PHHwB83X9DWTxl4Fkw8OrWS7pI0bkGZF6DBHzDg8+lAHgFr8dviLZkGLxx4gQg5H/ExlOPzate1/ak+LdmQYviFr6kcj/TWP8AOvMGjK9eOOc/XGKZQB7Rb/tnfG+1/wBV8S9eUjp/pOf5irsf7dPx+ixt+Kmvj/tsv/xNeFUUAe/r+31+0HH0+Kmu/jIn/wATTv8Ahv79oX/oquu/99p/8TXz9RQB9An9v79oVgR/wtTXP++0/wDiarSft2/H6YEP8VPEHPpMo/8AZa8HpRQB7RcftmfG+9ys3xM1+Tdxzc4/pWBf/tHfFPVZP9J+IGvzA+t865/I15vsyuRycdO9fYP7I/8AwTv8YftCSJr+vrJ4R8AIWkk1O6XZNcx9hCjfw4DEueABQBwHwD+H/wAZP2o/iFZ+G9A8Ra9NEzB73VLm+n+zWUGRmSQ7sd+F6kkCv0o/aE/au8H/ALAnwh0X4XeDJx4m8dWNkLa3huH80WzNl3uro55Z3LsE7k+leOfGj9sjwZ+zn4ZX4L/swaTFeawSLe4161QzETn5WMbAZmm5Pzn5QSxHSuu/Yv8A+Cc9xp+qWvxH+N8Taz4omdb2DRr5/N8lzyslyTnfKCANvRc85xQBzX7D/wCw/rvxO8XR/HD43LcapeXk/wDaGnaVqo3SXDlgVubhT27rH06EgAAV+nKxlcc5Pc9zQse0gg984xgdMf5FPoAKKKKAHUUUUANooooARhuBFIyhlZT/ABHLEdSf6U6igD5e/a9/YN8IftRadJqcQi8O+Oo4dkGswp8s+OiXCj76jjB6jmvij4a/tNfGX/gn/wCJk+H/AMW9FufE3gRzsgMzmRRCTgvbSnIdSM5jbpk8V+vFcp8RPhZ4V+LXhmfQPF2iWut6XMvMNxGGKN/eQ9Vb3GKAPgf4lfsXfBf9trwjN45+BOu2OheIZR5k+noNkDSAFmWWH70TknG4fLk+1fmj8XfgZ41+BfiiXQfGeiT6PeqzCOSQZhnUE4eNxwynHGPbpX3p8Zv2C/il+yj4kuPiH8A9b1DUNNtf3s1hC+b2FBlmVlHE0YGR64J713Pwj/be+GH7Wnh8/DL9oXw/ZaTrzMLVLy4Ty7eWbO3KMfmt5d2DjpkD0NAH5L7PlBzxim19u/tgf8E1vE3wTt7jxd4Febxj4DkHnGS3Tfc2cZGQXC/eT0Ye2etfEzQshIbAYEgjPTFAEdFFFACqNzADvxW14Q8Ga34+1+z0Tw9ptxq2r3jiOCztYy8jsSAMAduRz0Fdz+z9+zf4y/aQ8e2vhnwlYmYlg95qEikW9lF/FJI3YdgOrHpX6gSzfBT/AIJY/DaRIY18U/FLULdSgkx9suWI/iP/ACwt179zn1PABwfwJ/YH+Hv7MvhWL4o/tE6lYG6s8XUGhs+baBsDahHWaUsxIQcAkehrz/4qftSfFz9vDxRJ8MPgxpF3ofghFWGTySIWeEf8tLiUcRRdMRjsPY1X+HvwN+M3/BSjx1F44+Iup3GgfDyNwbchCibT/wAs7OI9eOTIeOfev1E+EPwT8HfAvwjb+G/Bujw6Xp0QXeVUGWdgB88r9XYkEnP940AeIfsg/sD+Df2Z9Ph1W6jh8ReO5E2zazImVtzjJW3U/dUdN3UkD0r6m8sBgQMcf/qH4U8/dI5OcZ3HPTpRQAUUUUAFFFFADqKTdRQAlFFFABRRRQAUnXg9KWigBrKXGCTg9cHGa+Uv2sP+Ce3gb9o22udY06KHwt45GXj1S0hAiuWx0njX72ePmHP4mvq+igD8kPg/+1T8UP2CvHsXwv8AjTY3et+BiPLhlJM7Qwk4822c8Sx85KHp0Hauj/bM/YH8NfE7we3xm+Aot760vIG1C80bTTuhuE6tNbAdG7tH65xzxX35+0F+zv4R/aR8A3XhfxXZqUwz2WoRKPtFjMRgSRn68leh6V+b/wCy/wDFzxb/AME+/wBo+9+DXxIuXbwTql4giuyx8iBpDiK9iz0R+A6/w891OQD855LdomCscN3U9ue9eo/s2/s5+Jv2lvihp/hHw9F5atie/wBQkUmGxtgfnlc/oF6sxAFfVf8AwVU/ZNs/hX4zsfiX4Us1g8OeKLpo763t1xFa32C25QOgmAdsDjcpx1FfX3wB8B+Hf+CeP7Gmo+NvFFvH/wAJNdWa6tqwzh5rqQKttYqfRSyr7MztQBjfGb4vfDn/AIJm/A2x8E+ArK2v/HF/EGijmIMksoBVry6I6gMPlXocccA58Z/ZC/YV1/8AaE8RD4zfHeW81K31CX7XZ6VfsRLfgnIkmHVIj2QdR7GsP9iL4D61+2l8aNe+O3xW/wCJjolvfE2tpMp8q9ulO5IgvQQQLgbe52j+9X6yRwiHYI/ljXgIBwBjGB7Y7fjQAywsYNLtYLSzhitbOFdscEKBEQYxhVHAHsO1WKKKACiiigAooooAKKKKACil20UAJRRRQAUUUUAFFFFABRRRQAc9utfDH/BWP4E2/j74CJ4+tIFbX/B0iSPMFy81lK2yRDj+6zLJ6YVvWvufO3mvOP2kdGh1z9nr4n6ZPtaK58LakjFh0b7I+0j3DDNAHgH7LF5Z/tofsO+DtP8AEUiXV/o2p2VnfzSDexbT7yGUE57yWwRSf+mrV4V/wWF+IeqeIvGHw2+DeibnnvWXVJrWM586eaQ21rHj22zH6uPSun/4Ip6xLP8AC34k6UTmC21q3u0HbdLblW/9Er+VecfG1P8AhMv+CzHhjTb474NL1HSPJVucCOzjuwP++yfzoA/ST4MfCvTfgp8L/DfgnSEVLTR7KK2aQLjzpQAZZT/tO5Yn6iu3pqqdq85xwM/hn9Qfzp1ABRRRQAUUUUAFFFFABRRRQA6iiigBtFFFABRRRQAUUUUAFFFFACfrXi37Znjy1+HP7K/xQ1e5kEfmaFc6fE2es9wDbRhfUh5FJ9hXs0rhYSzblXGfQj1+mBzX5Oft/fHrUP2uvi54b+BHwtH9r2FtqP8ApU9q26K6v8FWbcOPKhVpCW6Elj/CKAPcP+CMfg2fRP2f/FPiKeFkGseICkDMMebFBAilh7CSSUfVTXin7aF1/wAKN/4KheA/iDf5i0nUH0nUJ7nHyiJD9kn59QkWfoR61+mvwO+Fem/BD4T+GPBGlc2ui2gtzIFwZZOsrn3eQs31NfNX/BT79mG9+PXwXt9e0C1a58V+EpJLqC3iGZLm0bAniXHUjYjgf7BHU0AfYCsGwFO4Zb5l5B7j8xz+FOr4k/4JvfthWHxl+H9h4B8SXyRePPDtqLcCZ/m1K1jGElQn7zKqorjrwD3NfbKsDjnJJI456DNADqKKKACiiigAooooAKKKKAHUUUUANooooAKKKKACikpPORPMZmHlxcuxOFC4zkmgBd3y5wc917j61z/jv4g+Hfhj4evNe8UaxaaJpVmC0l1dSBF47DuTg9BzXyd+1J/wUx8C/BJrnw94P8rxz4xQGNo7d82lvMCQfMkH324+6vYg18v+C/2Xfj//AMFAvElv4r+K2tXfhfwez7oFuFKZj9La26DqfmbvmgDU/aG/bm8dftbeKP8AhU3wG0i/h0vUGFvNqEOUubqME7tzD/UwgZy2cn8q+vP2Hf2GNE/Za0A6tqnk6v4+1GEC81DZ8lojMG8iD0H949SeOlev/Av9mfwH+zr4bj0jwZo0NmSD9pvpfnubtiRlpH6np0HHA4r1Tb09unFADfLJXBOT0Ld/85odflJHbkL+n8qfRQB+aH7bX/BPXW9H8XXHxg+BZl03X4ZWvrzRdPPluJs7jPbY6E87k6E9PvEVr/sm/wDBT7SfFklt4P8AjAkfhXxVAwtk1aRDFb3L527ZVP8Aqnzjjpk5r9FWj9gSOhPWvl39qX/gn38Pv2k7a51IxL4b8Y7T5etWUQHmnqBOo4cZ79Rk9aAPo20vINQt0ntZo7mCRd6SwuGV1JGCCOoOc8VKuW7Zx97HQV+P1t4i/aT/AOCa+uR2erQSeLfh0snyGQtPYuvQ7H+9C3HQ8V97fs1ft0fDX9pG3t7TT9QGh+KCoD6FqcgSXd38o9JF+nPNAH0ZRTd3zEdx19qdQAUUUUAFFFFADqKKKAG0UUUAFKRjHPU01mCKWPQcmvE/2qP2rvCv7KvgV9Y11lvtaut0emaJE4826kHVj/djXjLe+BzQB2fxg+NnhD4D+D5PE3jLVk0rT14RPvTzPjOyKPq7dOB61+YvxC/aW+OP/BQPxfL4G+E2lXXh3wYrlJ2hkMYaIc77qccKP9haZ8H/ANn/AOKv/BST4hJ8Q/ibqk+leAY5MRKqlFdASTBaIeAufvSd/wAq/Vf4Y/CLwr8HfCtp4e8JaNa6PpdsuBHAmGkbPLu3V2POSfWgD5h/ZV/4Jo/D/wCAy2eteJIo/GnjRT/x9XcebW2bAJMUZ64II3Nz830r7KjtxFjbgAYAHbA/yacIzuySP5kjtUlABRRRQAUUUUAFBoooAoatoljrmnXFhqVpBf2E67Zba5jDxyD0YEYNfn9+09/wSl0XxJcXPif4PX3/AAiPiRGM39l+Yy2sj9QY3HMTZA56DrX6IfrSbfxPvQB+S/wb/wCCgHxP/Zh8XR/Dz9oLQ9QvLGBhEmpSxk3kEY4DKek6YweOfxr9NPAfxE8OfE/w1ZeIPC2r2utaPeKGhubWQMORnDDqp7YPNY3xy/Z38FftCeE5tC8Y6TDfR4YwXaqFuLViMBo5OoIPPpX5aeMPh78Yv+CXvxFXxL4Wu5vEvwzvpwku9WNtMmeIrlB/qnxnbIOv6UAfsPxgkHOP0oryH9mv9pvwj+074EtvEHhq58u6BWO/0m4YefYy7c+W/wDs5BKt0IxXrituVWHRsY9cEZzQA6iiigB1FJuooASk6Uo5OKa0nlqGJC/Lvy3QeufpQB5z+0F8dvD37Ofwx1Txn4jl/cWylLW0UjzLy4IOyJM/3sZz0ABJ6V+af7NPwJ8Xf8FFvjLqXxZ+KrzR+B7ecIlqhKx3DKSVtIPSNRje3fPqeK/xs8Ua1/wUj/bI0r4deFrqRPh54dmeKO8hJMa26MBc3p7EuQFTP+yO5r9a/h/4D0f4Z+D9J8M+HrGHTtG0uBbe2tohwoA5JPdicknqSaANPR9CsvD2mWenabbQ2VjaRrFFbwRhERFXAVVHAH0q/uozRg0ALRRketFABRS0lABRRRQAUUUUAFFFFACNypA4OPSsXxJ4V0zxh4fvtF12yt9U0q9i8m5tLiMPHKhHKkHr6g9Qea26Tb68igD8cf2gvgb45/4Ju/F7T/id8MpZrrwBeTiOSKQl0i3HJs7nHVTyEf27Ec/pj+z38ePDf7RXw00zxh4cn/d3CbLmwdgZbKcBfMhf/dJ+U9GGCK7jx58PtF+Ivg7VfDXiCxi1LRtSgaC4tJhlWUg8+xHGD2IB61+SPw/1vXv+CYn7Yl34U164muvhp4gkVTcSfdksnlIgux28yIkh8dg47igD9gqKjt7iK7t4biCVJoJkEkckZ3KynBUg9wQQfpUlABRS7aKAGtnBx17V8of8FLP2gpPgb+znqNrptx9n8R+LZH0exZGw0cR3G5lH0TC5HeRa+r2bapPpzX5MftkSz/tcf8FFPCPwis5Hl0DQpodLuFjOQgwLm/cejBBs+sQoA7v9l268P/8ABPP9jZfi94s0mW+8U+OLu3Nrp8bBJzaNua3iBboNivMx/wBpQegq4v8AwW38Nd/hrq3/AIGxV4Z/wV6+LaeI/jVovw30x1j0bwTpqRvBFwoup1RyMDjCwrAvsS1fAtAH63f8PtvC/f4baxj/AK/ov8KUf8FtfCmR/wAW11n/AMDov8K/JCigD9c/+H2/hLv8Ndax/wBf0X+FL/w+18Hf9E11v/wNhr8i6KAP13H/AAW28F5Gfhrr2P8Ar+hp/wDw+38Ef9E18Qf+BsH+FfkLRQB+vf8Aw+38Df8ARN9f/wDA2Cl/4fb+Bf8AonHiD/wMg/wr8gttG2gD9fv+H23gT/onPiD/AMC4aUf8FtfAn/ROvEH/AIFw1+QGPajFAH7Bf8PsvAX/AETzX/8AwLhpf+H2PgH/AKJ9r/8A4FQ1+PuKTbQB+wf/AA+x8A/9E+8Qf+BUNP8A+H2Hw+/6EHxAP+3iGvx6oxQB+w4/4LXfDvI/4oTxAP8AtvFWJ8cPFnhP/gpr+y/4v1vwbod1pfjj4eT/AGu1sboq9xLAYt7xgr1WRUkwv9+FfWvySxjmvsf/AIJW/F4/DX9qzStGupvL0rxhBJo06M2EE/Elu2O58xPLH/XU0Afd3/BK39oR/iv8DpfCGqXnn+IPBki2is7ZeXT3/wCPd89ypDRk+ir619tV+Rvg+3/4Yk/4Kg/2HGDaeD/FVyLWEN8sX2K/YNCB6LFchFz6Rt61+uIz6f5/zn8qAH0UUUAUdW1q18O6TfatfyCCysLeS6nkboiRqXcn6AGvyv8A+CU9m/xI/aG+Lnxn8RsgaxtpppZ5fupNeTtM7g+0cMo+jV9y/t1eMG8C/sh/FfVVfa82kPpynvm7kFuce+Jf0r4i/ZRmPwf/AOCV3xo8bMixXuvTXdpb3HQsjxQ2UWD/ALMkk345oA/Pb4vfEC4+KnxQ8VeMbsstzrmqXN+yMc+WsjllQewBC/QCuOpf8/pSUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFACjGRnp3rW8J+KL3wX4q0fxDpj+VqOl3kN9byf3ZYnWRT/AN9LWRRQB+pP/BXbw/beLPA3we+NWggxxXcf2Q3cZ+bZNEt3aEEemJvxIr9D/gl8Qo/it8I/BnjFCvma5pNtqEqxnIWWSJfMX/gLh1/A18Cayx+Nv/BGe1upB9p1Hwzbp5YHPlCzvfLz+FsT+Br2/wD4JR+Mv+Eq/Y90SyL+Y+garfaYxJ5Cl/PQflcD8qAPsSiiigD4s/4K3+IJdH/ZBubdWAXV9e0+zZfUASXGPziBr51+LzP4B/4I1/DnSh8h17UYFf8A2hJdXN6p/KMflXrf/BZy+Kfs8+CrMNtE3ipZT9Us51B/J6o/tAfAPxl8cf8AgnD+z/4W8C6X/auo21rouqTw7whWP+zJQTz/ALU6igD8f6K+oX/4Jp/tBx/8yPIf92dD/WoG/wCCbv7Qa/8AMg3J+kqf40AfMtFfSzf8E4/2hF/5p9eH6SJ/jTP+Hc/7Qn/ROr8/8DT/ABoA+bKK+kT/AME6/wBoUAn/AIVvqP8A30n+NM/4d4ftCf8ARNtT/NP/AIqgD5xor6N/4d4/tCf9E11T80/+KpP+HeX7Qv8A0TPVPzT/AOKoA+c6K+i/+Hen7Qn/AETLVvyT/wCKpP8Ah3r+0J/0TLVv/HP/AIqgD51or6K/4d6/tCf9Ey1b/wAc/wDiqP8Ah3r+0J/0TLVv/HP/AIqgD51or6I/4d7/ALQn/RMtW/JP/iqP+He/7Qn/AETLV/yT/wCKoA+d6K+iP+HfP7Qn/RMNY/JP/iqT/h3z+0L/ANEu1r/vlP8A4qgD54or6H/4d8/tC/8ARLta/wC+U/8AiqP+HfX7Qq8n4Xa1j/dT/wCKoA+0v+CdDf8ACc/8E8/jl4RdvOdP7XiiT+6J9NjK4/4Gjn8a1f8Agir4gNx8N/iXoJbIsdXs77aP+m0Dof8A0nH5V1f/AASz+Afj34Q+Cvijonj/AMN3fhyLVpbT7LHdgfvB5U6Sngn/AGPzryL/AIIm3T2+vfF+yfhnttLl29/lkuQT/wCRKAP1UooooA/OT/gtIzf8Kh+HhB+Q6/O23/t34/r+dfHnhH/gp58cvA/hXRfDuk6ppUWl6RZQ6faRyacjMsMUaxxgtnn5VGfpX2L/AMFpV2/B34e/7OvzD/yWNfkRQB9kf8PZv2hP+gxow/7hSf40f8PZv2hD11jRf/BUn+NfG9FAH2R/w9k/aD/6C+jf+CtP8aP+Hs37Qn/QY0b/AMFSf418b0UAfZP/AA9m/aF/6DOjf+CtP8aX/h7R+0J/0GNF/wDBUn+NfGtFAH2T/wAPZv2hf+gxov8A4Kk/xpf+HtH7Q3/QY0X/AMFSf418a0UAfZH/AA9n/aF/6DOjf+CtP8aX/h7R+0L/ANBnRf8AwVp/jXxtRQB9k/8AD2j9oX/oM6L/AOCtP8aP+HtH7Qv/AEGdF/8ABWn+NfG1FAH2R/w9m/aF/wCgzo3/AIK0/wAaP+Hs37Qv/QZ0b/wVp/jXxvRQB9k/8PaP2hv+g1o3/grT/Gj/AIe0ftC/9BjRf/BWn+NfG1FAH2T/AMPaP2hf+gxov/grT/Gj/h7R+0L31jRcf9gtP8a+NqKAPsr/AIezftBsCG1fRznj/kFp079/pXsX/BFe4a5+I3xScgDfplo7AdMmdjwO2Oa/NSv0m/4Inf8AJQfih/2CbP8A9HNQB+tNFFFAHwz/AMFWvg741+M3wy8F6d4I8N33iW8s9ZmuZ4rFQxjTySgJyR1NfmX/AMMK/H7/AKJXr/8A36X/AOKr+hdfuH/rmv8AOm0Afz1f8MK/H7/olev/APfpf/iqP+GFfj9/0SvX/wDv0v8A8VX9CtFAH89X/DCvx+/6JXr/AP36X/4qj/hhX4/f9Er1/wD79L/8VX9CtFAH89X/AAwr8fv+iV6//wB+l/8AiqP+GFfj9/0SvX/+/S//ABVf0K0UAfz1f8MK/H7/AKJXr/8A36X/AOKo/wCGFfj9/wBEr1//AL9L/wDFV/QrRQB/PV/wwr8fv+iV6/8A9+l/+Ko/4YV+P3/RK9f/AO/S/wDxVf0K0UAfz1f8MK/H7/olev8A/fpf/iqP+GFfj9/0SvX/APv0v/xVf0K0UAfz1f8ADCvx+/6JXr//AH6X/wCKo/4YV+P3/RK9f/79L/8AFV/QrRQB/PV/wwr8fv8Aolev/wDfpf8A4qj/AIYV+P3/AESvX/8Av0v/AMVX9CtFAH89X/DCvx+/6JXr/wD36X/4qj/hhX4/f9Er1/8A79L/APFV/QrRQB/PV/wwt8fRyfhZr4Hc+Uv/AMVX3l/wSg/Z++IvwV8cfEG58ceEtQ8NW99ptrFbyXyBRI6yklRgnnBr9Im+6fpTm/1o/wB8/wAqAJKKKKAP/9k=";
    
}