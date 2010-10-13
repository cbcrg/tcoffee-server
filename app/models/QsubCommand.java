package models;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import play.Logger;
import play.Play;
import util.Check;
import util.FileIterator;
import util.Utils;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.annotations.XStreamAsAttribute;
import com.thoughtworks.xstream.annotations.XStreamImplicit;
import com.thoughtworks.xstream.annotations.XStreamOmitField;

import exception.CommandException;

/**
 * Execute the specified command on the system cluster 
 * 
 * @author Paolo Di Tommaso
 *
 */
@XStreamAlias("qsub")
public class QsubCommand extends AbstractShellCommand {
	
	@XStreamImplicit
	List<AbstractShellCommand> _commands;

	@XStreamAsAttribute
	String queue;
	
	@XStreamAsAttribute
	String jobname;
	
	/** the qsub script filename */
	String scriptfile;
	
	@XStreamOmitField private File fPbsFile; 
	@XStreamOmitField private AbstractShellCommand command;

	private String jobid;
	
	public Boolean disabled;
	
	/** The default constructor */
	public QsubCommand() {}
	
	public QsubCommand( QsubCommand that ) {
		super(that);
		this._commands = Utils.copy(that._commands);
		this.queue = that.queue;
		this.jobname = that.jobname;
		this.scriptfile = that.scriptfile;
	}
	
	String getQueue() {
		return queue;
	}
	
	String getJobName() {
		return jobname;
	}
	
	File getPbsFile() {
		return fPbsFile;
	}
	
	String getJobId() {
		return jobid;
	}
	
	public boolean getDisabled() { 
		if( disabled != null ) { 
			return disabled;
		}
		
		String result = AppProps.instance().getProperty("qsub.disabled");
		disabled = result != null ? "true".equals(result) : Play.mode.equals( Play.Mode.DEV );
		return disabled;
	}
	
	@Override
	public void init(CommandCtx ctx) {
		
		command =  _commands != null && _commands.size()>0 ? _commands.get(0) : null;
		Check.notNull(command, "Missing nested command");

		
		/*
		 * initialize the target command 
		 */
		command.init(ctx);
		
		if( disabled ) {
			return;
		}
		
		/*
		 * initialize this command 
		 */
		if( Utils.isEmpty(logfile) ) logfile = "_qsub.out.log";
		if( Utils.isEmpty(errfile) ) errfile = "_qsub.err.log";
		if( Utils.isEmpty(envfile) ) envfile = "_qsub.env.txt";
		if( Utils.isEmpty(cmdfile) ) cmdfile = "_qsub.cmd.txt";
		if( Utils.isEmpty(scriptfile) ) scriptfile = "_qsub.pbs";
		
		super.init(ctx); 
	}
	
	@Override
	protected void onInitEnv(Map<String, String> map) {
		
		super.onInitEnv(map);

		/*
		 * add the SGV_ROOT env variable if exists 
		 */
		addIfNotEmpty(map, "SGE_ROOT");
		addIfNotEmpty(map, "SGE_EXECD_PORT");
		addIfNotEmpty(map, "SGE_CLUSTER_NAME");
		addIfNotEmpty(map, "SGE_QMASTER_PORT");
		addIfNotEmpty(map, "SGE_CELL");
	
	}
	
	private void addIfNotEmpty(Map<String,String> map, String key) {
		if( map.containsKey(key)) return;
		
		String val = AppProps.instance().getString(key);
		if( Utils.isNotEmpty(val)) {
			map.put(key,val);
		}
	}
	
	@Override
	protected String onInitCommandLine(String cmdLine) {
		
		/*
		 * 0. validation 
		 */
		if( Utils.isEmpty(queue) ) {
			queue = AppProps.instance().getString("SGE_QUEUE");
			Check.notEmpty(queue, "Qsub queue parameter cannot be empty");
		}
		
		if( Utils.isEmpty(jobname)) {
			jobname = "tserver";
		}
 		
		/*
		 * 1. create the PBD script file to be submited in the cluster queue
		 */
		fPbsFile = new File(ctxfolder,scriptfile);
		StringBuilder script = new StringBuilder();
		script.append("#!/bin/sh\n");

		/*
		 * 2. fetch the env vars from the target command
		 *    and add them to t  
		 */
		Map<String,String> targetEnv = new HashMap<String, String>();
		command.onInitEnv(targetEnv);
		
		if( targetEnv != null && targetEnv.size()>0 ) {
			for( Map.Entry<String,String> entry : targetEnv.entrySet() ) {
				script.append("#$ -v ");
				script.append(entry.getKey()).append("=").append(entry.getValue());
				script.append("\n"); // <-- don't forget the blank
			}
		}

		/* write the command and save */
		script.append(command.getCmdLine());
		Utils.write(script, fPbsFile);
	
		

		/*
		 * 3. return the qsub command 
		 */
		File targetOutFile = command.getLogFile();
		File targetErrFile = command.getErrFile();
		StringBuilder result = new StringBuilder();
		result .append("qsub ");
		result .append("-cwd ");
		result .append("-sync y ");
		result .append("-now y ");
		result .append("-r no ");
		result .append("-terse ");
		result .append("-q ") .append(queue) .append(" ");

		if( targetOutFile != null ) {
			result .append("-o ") .append(targetOutFile.getName()) .append(" ");
		}

		if( targetErrFile != null ) {
			result .append("-e ") .append(targetErrFile.getName()) .append(" ");
		}
		
		if( Utils.isNotEmpty(jobname) ) {
			result.append("-N ") .append(jobname) .append(" ");
		}

		result.append( fPbsFile.getName() );
		return  result.toString();
	}
	
	@Override
	public boolean run() throws CommandException {
		return (disabled)
			 	? command.run()
				: super.run();
	}
	
	@Override
	protected boolean done(boolean success) {
		
		/*
		 * complete the target command
		 */
		success = command.done(success);
		
		/* 
		 * the result of this command is the same as the target command 
		 */
		result = command.result;

		if( disabled ) { 
			return success;
		}
	
		/*
		 * complete this job parsing the qsub output
		 */
		parseResultFile();
		if( hasErrors() ) {
			success = false;
		}
		
		return success;
	}

	private boolean hasErrors() {
		
		final String ERROR = "Your qsub request could not be scheduled, try again later.";
		
		File file = getErrFile(); 
		if( file == null || !file.exists() ) {
			/* no error file so .. no errors ! */
			return false;
		}
		
		for( String line : new FileIterator(file) ) {
			if( line.contains(ERROR)) {
				result.clearErrors();
				result.addError(ERROR);
				return true;
			}
		}

		return false;
	}

	private void parseResultFile() {
		BufferedReader reader;
		try {
			reader = new BufferedReader(new FileReader(getLogFile()));
			jobid = reader.readLine().trim();  // <-- by definition the first line contains the submitted job-id 
			reader.close();
		} 
		catch (IOException e) {
			Logger.error(e, "Error on parsing qsub result file: ", getLogFile());
		}
	}
	
}
