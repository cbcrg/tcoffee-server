package job;

import java.io.File;
import java.io.IOException;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;

import models.UsageLog;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.blackcoffee.commons.utils.FileIterator;

import play.Logger;
import play.Play;
import play.jobs.Job;
import util.Utils;

public class UsageImportJob extends Job  {

	private File fileToImport;

	public UsageImportJob( File fileToImport ) { 
		this.fileToImport = fileToImport;
	}
	
	@Override
	public void doJob() { 
		
		
		/* 
		 * import the file to the database
		 */
		try { 
			doTheWork(fileToImport);
		}
		catch( Exception e ) { 
			Logger.error(e, "Unable to import usage file: %s", fileToImport);
		}
		
		/* 
		 * backing up the imported file 
		 */
		
		if( Play.mode.isProd() ) { 
			try {
				File bak = new File(fileToImport.getParentFile(), fileToImport.getName()+".bck");
				FileUtils.moveFile(fileToImport, bak);
			} 
			catch (IOException e) {
				Logger.error(e, "Failed to backup usage file");
			}
		}
		Logger.info("Usage file import DONE");
	}

	static void doTheWork(File fileToImport) {
		int i=0;
		for( String line : new FileIterator(fileToImport) ) { 
			try {
				if( line.startsWith("#")) { continue; } 
				
				StatRow row = new StatRow(line);
				
				UsageLog log = new UsageLog();
				log.bundle = row.bundle;
				log.creation = new Timestamp(row.recordTime);
				log.duration = row.duration;
				log.elapsed = row.elapsed;
				log.ip = row.ip;
				log.requestId = row.rid;
				log.service = row.service;
				log.status = row.status;
				log.lng = row.lng;
				log.lat = row.lat;
				log.countryCode = row.countryCode;
				log.country = row.country;
				log.city = row.city;
				log.locationProvider = row.locationProvider;
				log.history = "1";
				log.sessionId = ( i % 2 == 0 ) ? "123" : "321";
				log.email = i < 5 ? "tcoffee.msa@gmail.com" : null;
				
				log.save();
				Logger.info("Import usage row: %s", ++i);
			} 
			catch (ParseException e) {
				Logger.error("Error importing log line: \"%s\"", line);
			}
			
			
		}

	}

	static class StatRow  { 
		String date;
		String ip;
		String bundle;
		String service;
		String rid;
		String duration;
		String status;
		long elapsed;
		long recordTime;
		String lng;
		String lat;
		String countryCode;
		String country;
		String city;
		String locationProvider;

		static final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd");

		public StatRow( String line ) throws ParseException { 
			String[] items = line.split(",");
			date =  	items.length>0 ? items[0] : null ;
			ip = 		items.length>1 ? items[1] : null ;
			bundle = 	items.length>2 ? items[2] : null ;
			service = 	items.length>3 ? items[3] : null ;
			rid = 		items.length>4 ? items[4] : null ;
			duration =  items.length>5 ? items[5] : null ;
			status = 	items.length>6 ? items[6] : null ;
			elapsed = 	items.length>7 && StringUtils.isNotBlank(items[7]) ? Long.parseLong(items[7]) : 0L;

			/* duration string is in the format '10 sec' */
			if( Utils.isNotEmpty(duration) && duration.contains(" ") && elapsed==0 ) { 
				elapsed = Utils.toDuration(duration) / 1000;
			}
			
			/* duration string contains a number */
			else if( Utils.isNotEmpty(duration) && !duration.contains(" ") && elapsed==0) { 
				elapsed = Long.parseLong(duration);
				duration = Utils.asDuration(elapsed);
				elapsed = elapsed / 1000; 
			}
			
			/* 
			 * duration is empty (should never happens) and elapsed contains the duration as seconds
			 */
			else if( Utils.isEmpty(duration)  && elapsed > 0 ) { 
				duration = Utils.asDuration(elapsed * 1000);
			}
			
			recordTime = fmt.parse(date.substring(0,10)).getTime();
			
			
			this.lng = 					items.length>8 ? items[8] : null ;
			this.lat = 					items.length>9 ? items[9] : null ;
			this.countryCode = 			items.length>10 ? items[10] : null ;
			this.country = 				items.length>11 ? items[11] : null ;
			this.city = 				items.length>12 ? items[12] : null ;
			this.locationProvider = 	items.length>13 ? items[13] : null ;
			
		}
		


	}	
	
}


 
