package models;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.io.FileUtils;

import play.Logger;
import play.libs.IO;
import util.Utils;

import com.thoughtworks.xstream.annotations.XStreamOmitField;

import exception.CommandException;
import exception.QuickException;

public abstract class AbstractShellCommand extends AbstractCommand<OutResult> {

	/** Xml specified environment valiables */
	public Env env;
	
	/** The output file name */
	public String logfile;

	/** The error file name */
	public String errfile;
	
	/** The file name to be used to store the cmd file*/
	public String cmdfile;

	/** The file name to be used to store the cmd file*/
	public String envfile;

	/** The exit code return when the program terminate without error */
	public int validCode = 0; 
	
	/** The number of seconds after that kill the process */
	public long timeout = 0;
	
	/* the job context folder */
	@XStreamOmitField
	private File fLocalFolder;

	/* the resolved command line to be executed */
	@XStreamOmitField
	private String fCmdLine;

	@XStreamOmitField
	private Map fEnv;

	@XStreamOmitField
	private File fLogFile;
	
	@XStreamOmitField
	private File fErrFile;
	
	@XStreamOmitField
	private File fCmdFile;
	
	@XStreamOmitField
	private File fEnvFile;
	
	@XStreamOmitField
	private Integer fExitCode;

	@XStreamOmitField
	private FileOutputStream fLogStream;

	@XStreamOmitField
	private FileOutputStream fErrStream;

	/** The default constructor */
	public AbstractShellCommand() { }
	
	/** The copy constructor */
	public AbstractShellCommand(AbstractShellCommand that) {
		super(that);
		this.env = Utils.copy(that.env);
		this.logfile = Utils.copy(that.logfile);
		this.errfile = Utils.copy(that.errfile);
		this.cmdfile = Utils.copy(that.cmdfile);
		this.envfile = Utils.copy(that.envfile);
		this.validCode = that.validCode;
	}
	
	
	public File getLogFile() {
		return fLogFile;
	}
	
	public File getErrFile() {
		return fErrFile;
	}
	
	public File getEnvFile() {
		return fEnvFile;
	}

	public File getCmdFile() {
		return fCmdFile;
	}
	
	public boolean existsLogFile() {
		return fLogFile != null && fLogFile.exists();
	}
	
	public boolean existsErrFile() {
		return fErrFile != null && fErrFile.exists();
	}

	public boolean existsCmdFile() {
		return fCmdFile != null && fCmdFile.exists();
	}

	public boolean existsEnvFile() {
		return fEnvFile != null && fEnvFile.exists();
	}
	
	
	protected File getLocalFolder() {
		return fLocalFolder;
	}
	
	/** 
	 * Template method to intercept and manipulate command line before the command execution 
	 * 
	 * @param cmdLine the current command line string 
	 * @return the command line to be executed
	 */
	protected String onInitCommandLine( String cmdLine ) {
		return cmdLine;
	}
	
	
	String getCmdLine() {
		return fCmdLine;
	}
	
	final private void prepareCommandLine() {
    	fCmdLine = onInitCommandLine(null);

    	// save the command lie if the associated file has been specified 
    	if( fCmdFile != null ) {
        	try {
				IO.writeContent(fCmdLine, fCmdFile);
			} catch (IOException e) {
				throw new QuickException(e, "Fail saving CMD file named: %s", fCmdFile);
			}
    	}

	}

	/**
	 * Template method to intercept and modify environment configuration before the command execution
	 */
	
	protected void onInitEnv( Map<String,String> map  ) {
		if( env == null ) return;
		
		for( String name : env.getNames() ) {
			String value = env.get(name);
			if( Utils.isNotEmpty(name) && Utils.isNotEmpty(value) ) {
				map.put(name, value);
			}
		}
	}
		
	
	final private void prepareEnvironment() {
		
		/* configure the local env */
		Map<String,String> local = new HashMap<String, String>();
		onInitEnv(local);
		
    	fEnv = local;
    	
    	/* if it has been provided a file name, store the env configuration in a file */
    	if( fEnvFile != null ) {
    		/* copy the current environment */
    		Map<String,String> all = new HashMap<String, String>(System.getenv());
    		/* merge, overriding the system env with the task provided env */
    		all.putAll(local);

    		try {
    			all = new TreeMap<String, String>(all); // sort by natural ky 
    			String sEnv = Utils.asString(all, "\n");
    			IO.writeContent(sEnv, fEnvFile);
    		} 
    		catch (IOException e) {
    			throw new QuickException(e, "Fail on save ENV file named: '%s'", envfile);
    		}
    	}

	}
	
	
	/**
	 * Initialize the job before the command execution
	 */
	@Override
	protected void init( CommandCtx ctx ) {
		super.init(ctx);
		/* 
		 * initialize all required files 
		 */
		// main local folder 
		fLocalFolder = Module.current().repo().getFile();
		// environment variables file 
		if( Utils.isNotEmpty(envfile) ) {
			fEnvFile = new File(fLocalFolder,envfile);
		}
		// command line file 
		if( Utils.isNotEmpty(cmdfile) ) {
			fCmdFile = new File(fLocalFolder,cmdfile);
		}
		// log/output file 
		if( Utils.isNotEmpty(logfile) ) {
			fLogFile = new File(fLocalFolder,logfile);
		}
		// err file 
		if( Utils.isNotEmpty(errfile) ) {
			fErrFile = new File(fLocalFolder,errfile);
		}
		
		
		/* 1. prepare the env  */
		prepareEnvironment();
		
    	/* 2. prepare and save the command line */
		prepareCommandLine();

		result = new OutResult();
		
	}	
	
	/** 
	 * The main job execution method 
	 */
	@Override
	public boolean run() throws CommandException { 
		
		
		
		try { 
			/*
			 * 1. prepare the stream handler
			 */
			fLogStream = (fLogFile!=null) ? new FileOutputStream(fLogFile) : null;
			fErrStream = (fErrFile!=null) ? new FileOutputStream(fErrFile) : null;			
			
			try {
				/* 
		    	 * 2. prepare the command line and the executor
		    	 */
				DefaultExecutor executor = new DefaultExecutor();
				executor.setWorkingDirectory( fLocalFolder );
				executor.setExitValue( validCode );
				executor.setStreamHandler(new PumpStreamHandler(fLogStream, fErrStream));
				
				/*
				 * 3. set a watchdog if a timeout has been specified
				 */
				if(timeout > 0) { 
					executor.setWatchdog(new ExecuteWatchdog(timeout));
				}
				
				/* 
				 * 4. wrap the command in a bach script to provide env variables 
				 */
				StringBuilder shell = new StringBuilder();
				for( Object item : fEnv.keySet()) {
					String name = (String) item;
					String value = (String) fEnv.get(name);
					shell.append("export ") .append(name) .append("=") .append(value) 
					     .append("\n");
				}
				shell.append( fCmdLine );
				
				FileUtils.writeStringToFile(new File(fLocalFolder, "_run.sh"), shell.toString());
				
		        /*
		         * 5. run the command    
		         */
				CommandLine cmd = CommandLine.parse("bash _run.sh");
				fExitCode = executor.execute(cmd);		
			} 
			finally {
				/* 
		         * close open stream 
		         */
				if( fLogStream != null ) try { fLogStream.close(); } catch(IOException e) { Logger.warn("Error closing log stream on file: %s", fLogFile); } 
		        if( fErrStream != null ) try { fErrStream.close(); } catch(IOException e) { Logger.warn("Error closing err stream on file: %s", fErrFile); } 

			}


			return fExitCode != null && fExitCode == validCode;
		}
		catch( Exception e ) { 
			throw new CommandException(e, "Fail on executing command: '%s' ", fCmdLine);
		}
	}
	
	
}
