package main.java.com.test.resource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.InvalidKeyException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.InvalidAttributeValueException;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.InstanceProfileCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.amazonaws.services.s3.model.S3ObjectSummary;

@Path("")
public class SimpleRest {

    @Context
    UriInfo uriInfo;

    private static Logger LOGGER = LoggerFactory.getLogger(SimpleRest.class);
    private static Pattern datePattern = Pattern.compile("[0-9]{2}\\-[0-9]{2}\\-[0-9]{4}");

    private AmazonS3 getS3Client() {
		AmazonS3 s3 =  new AmazonS3Client(new InstanceProfileCredentialsProvider());		
		Region usWest2 = Region.getRegion(Regions.US_WEST_2);
        s3.setRegion(usWest2);
        return s3;
    }
    
    @SuppressWarnings("unused")
	private void listBuckets(AmazonS3 s3) {
    	for (Bucket bucket : s3.listBuckets()) {
            System.out.println(" - " + bucket.getName());
        }
        System.out.println();
    }
    
    private DateTime parseDate(String dateString) throws Exception {
    	Matcher m = datePattern.matcher(dateString);
    	if (!m.matches()) {
    		throw new InvalidKeyException();
    	}
    	String[] splitDate = dateString.split("-");
    	int month = Integer.parseInt(splitDate[0]);
    	int date = Integer.parseInt(splitDate[1]);
    	int year = Integer.parseInt(splitDate[2]);
    	DateTime dateTime = new DateTime().withYear(year).withMonthOfYear(month).withDayOfMonth(date);
    	return dateTime;
    }
    
    @GET
    @Path("/getNews")
    @Produces({MediaType.APPLICATION_JSON})
    public Response getNews(@QueryParam("start") String startDate, @QueryParam("end") String endDate, @QueryParam("max") Integer maxCount) throws JSONException, IOException {
    	
    	// Creating an S3 client
    	AmazonS3 s3 = getS3Client();
    	
    	DateTime dtStart;
    	DateTime dtEnd;
    	
    	// Parsing input dates
    	// Expects the start dates and return 500 internal server error with a message if the data is either not given or formatted wrong
		try {
			dtStart = parseDate(startDate);
		}
    	catch (Exception e) {
    		return Response.serverError().entity(getMessageJson(e.getClass().getSimpleName(), "Date (start) should be in mm-dd-yyyy format")).build();
    	}
		
		// Expects the start dates and return 500 internal server error with a message if the data is either not given or formatted wrong
		try {
			dtEnd = parseDate(endDate);
		} catch (Exception e) {
			e.getClass();
			return Response.serverError().entity(getMessageJson(e.getClass().getSimpleName(), "Date (end) should be in mm-dd-yyyy format")).build();
		}
		
		// Returns 500 internal server error with a message if max is <=0
		if (maxCount < 1) {
			return Response.serverError().entity(getMessageJson(InvalidAttributeValueException.class.getSimpleName(), "max should be greater than 0")).build();
		}
		int count = 0;
        
    	// The bucket name from which we need to return documents
    	String bucketName = "nyunewsoregon";
        
        List<String> requiredKeys = new ArrayList<String>();
        
        ObjectListing objectListing=null;
        
        try {
        	objectListing = s3.listObjects(new ListObjectsRequest().withBucketName(bucketName));
        }
        catch (AmazonClientException e) {
        	return Response.serverError().entity(getMessageJson(e.getClass().getSimpleName(), "Problem with S3 Access")).build();
        }
		
		for (S3ObjectSummary objectSummary : objectListing.getObjectSummaries()) {
			if (objectSummary.getKey().endsWith("/") ) {
				// Skipping directory objects
				continue;
			}
			DateTime dtLastmodified = new DateTime(objectSummary.getLastModified());
			if (dtLastmodified.isAfter(dtStart) && dtLastmodified.isBefore(dtEnd)) {
				requiredKeys.add(objectSummary.getKey());
			}
            if (requiredKeys.size() == maxCount) {
            	LOGGER.info("Number of documents in the bucket exceeded the maximum, which is "+maxCount);
            	break;
            }
        }
		
		// If the number of documents is very large, S3 list call truncates the result
		// The following code is to handle that use case
		while (objectListing.isTruncated()) {
			if (count == maxCount) {
            	break;
            }
			objectListing = s3.listNextBatchOfObjects(objectListing);
			for (S3ObjectSummary objectSummary : objectListing.getObjectSummaries()) {
				if (objectSummary.getKey().endsWith("/") ) {
					// Skipping directory objects
					continue;
				}
				DateTime dtLastmodified = new DateTime(objectSummary.getLastModified());
				if (dtLastmodified.isAfter(dtStart) && dtLastmodified.isBefore(dtEnd)) {
					requiredKeys.add(objectSummary.getKey());
				}
	            if (requiredKeys.size() == maxCount) {
	            	LOGGER.info("Number of documents in the bucket exceeded the maximum, which is "+maxCount);
	            	break;
	            }
	        }
		}
		
		// Creating a JSON with the required output, to return
		JSONObject jsonObject = new JSONObject();
		JSONObject newsObject = new JSONObject();		
		
		for (String key: requiredKeys) {
			S3Object object = s3.getObject(new GetObjectRequest(bucketName, key));
			String text = getTextFromInputStream(object.getObjectContent());
			newsObject.put(key, text);
			LOGGER.info("Document "+key+" added to output payload");
		}
		
		jsonObject.put("news", newsObject);
		
		// returning a HTTP response
		return Response.ok(jsonObject).build();
	}
    
    private JSONObject getMessageJson(String exceptionName, String message) throws JSONException {
    	JSONObject jsonObject = new JSONObject();
    	jsonObject.put("exception", exceptionName);
    	jsonObject.put("message", message);
		return jsonObject;
	}

	private String getTextFromInputStream(S3ObjectInputStream objectContentStream) throws IOException {
    	BufferedReader reader = new BufferedReader(new InputStreamReader(objectContentStream));
    	StringBuffer sb = new StringBuffer();
        while (true) {
            String line = reader.readLine();
            if (line == null) break;
            sb.append(line);
        }
        return sb.toString();
	}

}
