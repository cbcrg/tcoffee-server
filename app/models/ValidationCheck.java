package models;


import java.io.FileWriter;
import java.io.Serializable;
import java.util.Date;
import java.util.regex.Pattern;

import org.blackcoffee.commons.format.Alphabet;
import org.blackcoffee.commons.format.Clustal;
import org.blackcoffee.commons.format.Fasta;

import play.Logger;
import play.data.validation.EmailCheck;
import play.data.validation.Validation;
import play.mvc.Http;
import play.mvc.Http.Request;
import plugins.AutoBean;
import util.Utils;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.annotations.XStreamAsAttribute;
import com.thoughtworks.xstream.annotations.XStreamOmitField;

/**
 * Models a field validation rule. 
 * 
 * @author Paolo Di Tommaso
 *
 */
@AutoBean
@XStreamAlias("validation")
public class ValidationCheck implements Serializable {

	/** 
	 * Defines the field as required
	 */
	@XStreamAsAttribute
	public boolean required;

	/** Error message displayed when entered data does not match the specified {@link #required} constraint */
	@XStreamAlias("required-error")
	public String requiredError;
	
	
	/** 
	 * Specifies the format that must be used to entered the value in the field. The valid values are the following:
	 * 	<li>TEXT</li> 
	 * 	<li>EMAIL</li>
	 * 	<li>INTEGER</li>
	 * 	<li>DECIMAL</li>
	 * 	<li>DATE</li>
	 * 	<li>FASTA</li>
	 * 	<li>CLUSTAL</li>
	 *  
	 */
	@XStreamAsAttribute
	public String format; 
	
	/** Error message displayed when entered data does not match the specified {@link format} */
	@XStreamAlias("format-error")
	public String formatError;
	
	/** 
	 * Sub-type for format 'FASTA'. Valida types are: 
	 * <li>amino-acid</li> (default)
	 * <li>nucleic-acid</li>
	 * <li>dna</li>
	 * <li>rna</li>
	 * 
	 */
	@XStreamAsAttribute
	public String type;
	
	/** minimum value accepted for numbers and date and string values */
	@XStreamAsAttribute
	public String min; 

	/** Error message displayed when entered data does not match the specified {@link #min} constraint */
	@XStreamAlias("min-error")
	public String minError;	
	
	/** maximum value accepted for numbers and date and string values */
	@XStreamAsAttribute
	public String max;
	
	/** Error message displayed when entered data does not match the specified {@link #max} constraint */
	@XStreamAlias("max-error")
	public String maxError;	
	
	/** A regular expression to match */
	@XStreamAsAttribute
	public String pattern; 

	/** Error message displayed when entered data does not match the specified {@link #pattern} constraint */
	@XStreamAlias("pattern-error")
	public String patternError;	
	
	
	/** Max number of sequences (only for FASTA format) */
	@XStreamAsAttribute
	@XStreamAlias("maxnum")
	public Integer maxNum;
	
	/** Error message displayed when entered data does not match the specified {@link #maxNum} constraint */
	@XStreamAlias("maxnum-error")
	public String maxNumError;	

	/** Max number of sequences (only for FASTA format) */
	@XStreamAsAttribute
	@XStreamAlias("minnum")
	public Integer minNum;
	
	/** Error message displayed when entered data does not match the specified {@link #maxNum} constraint */
	@XStreamAlias("minnum-error")
	public String minNumError;		
	
	/** Max length of sequence (only for FASTA format) */
	@XStreamAsAttribute
	@XStreamAlias("maxlen")
	public Integer maxLength;
	
	/** Error message displayed when entered data does not match the specified {@link #maxLength} constraint */
	@XStreamAlias("maxlen-error")
	public String maxLengthError;	

	/** Min length of sequence (only for FASTA format) */
	@XStreamAsAttribute
	@XStreamAlias("minlen")
	public Integer minLength;
	
	/** Error message displayed when entered data does not match the specified {@link #maxLength} constraint */
	@XStreamAlias("minlen-error")
	public String minLengthError;		
	
	/** All the sequences MUST have the same length */
	@XStreamAsAttribute
	@XStreamAlias("samelen")
	public boolean sameLength;
	
	/** The string displayed when the same length check does not pass */ 
	@XStreamAlias("samelen-error")
	public String sameLengthError;	
	
	
	/** Validation rule expressed by groovy scripting */
	@XStreamAlias("script")
	public Script script;
	
	@XStreamOmitField 
	String fErrorMessage;
	
	@XStreamOmitField 
	Object fNormalizedValue;
	
	@XStreamOmitField 
	Boolean fIsValid;
	
	/** 
	 * Default empty constructor 
	 */
	public ValidationCheck() { }
	

	
	Integer getMinAsInteger() {
		return Utils.parseInteger(min,null);
	}
	
	Integer getMaxAsInteger() {
		return Utils.parseInteger(max,null);
	}
	
	Date getMinDate() {
		return Utils.parseDate(min,null);
	}
	
	Date getMaxDate() {
		return Utils.parseDate(max,null);
	}
	
	Double getMinDecimal() {
		return Utils.parseDouble(min,null);
	}

	Double getMaxDecimal() {
		return Utils.parseDouble(max,null);
	}

	public void apply(String name, String value) {

		ErrorWrapper error = null;

		/* 
		 * apply REQUIRED constraint validation
		 */
		if( required && Utils.isEmpty(value)) 
		{
			error = applyRequiredValidation(name, value);
		}
		
		/* 
		 * if there are not error, check for 'format' constraint
		 */
		if( error == null ) { 
			String[] all = this.format != null ? this.format.split("\\|") : new String[] {"none"};
			for( int i=0; all!=null && i<all.length ; i++ )
			{ 
				error = applyFormatValidation( all[i].trim(), name, value);
				if( error == null ) { 
					// the first that is NOT failing is good (- OR condition -) 
					break;
				}
			}
		}

		/*
		 * if any, report the error 
		 */
		if( error != null ) { 
			logError(name, error.message, value);
			Validation.addError(error.fieldName, error.message, error.variables);
		}
		

		fIsValid = (error == null);
	}

	private void logError(String name, String message, String value) {
		if( AppProps.VALIDATION_LOG_FILE == null ) { 
			return;
		}

		try { 
			FileWriter out = new FileWriter(AppProps.VALIDATION_LOG_FILE,true);
			String msg = String.format(
					"\nFailed validation for field '%s::%s' with the following message: '%s' \n" +
					">>>> BEGIN >>>>\n" +
					"%s\n" +
					"<<<<< END <<<<<\n\n", Service.current().name, name, message, value);
			
			out.write( Utils.DATE_TIME_FORMAT.format(new Date()) );
			if( Request.current() != null ) { 
				out.write(" - ");
				out.write( Request.current().remoteAddress ); 
				Http.Header agent = Request.current().headers.get("user-agent");
				if( agent != null ) { 
					out.write(" - ");
					out.write( agent.value() );
				}
			}

			out.write(msg);
			out.close();
			
		} catch( Exception e) { 
			Logger.warn(e, "Error logging validation error");
		}
	}



	ErrorWrapper applyFormatValidation(String format, String name, String value) {

		ErrorWrapper error=null;
		
		/*
		 * TEXT format validation
		 */
		if( isFormatText(format) ) 
		{
			error = applyTextValidation(name, value);
		}
		
		/*
		 * EMAIL FIELDS 
		 */
		else if( isFormatEmail(format)  ) 
		{ 
			error = applyEmailValidation(name, value);
		}
		
		/*
		 * DATE  validation
		 */
		else if( isFormatDate(format) && Utils.isNotEmpty(value) ) 
		{
			error =  applyDateValidation(name, value);
		}
		/* 
		 * INTEGER number validation
		 */
		else if( isFormatInteger(format) && Utils.isNotEmpty(value)) 
		{
			error = applyIntegerValidation(name, value);
		}
		/*
		 * DECIMAL number validation
		 */
		else if( isFormatNumber(format) && Utils.isNotEmpty(value) ) 
		{
			error = applyDecimalValidation(name, value);
		}
		
		/*
		 * FASTA format validation
		 */
		else if( isFormatFasta(format) && Utils.isNotEmpty(value) ) 
		{
			error = applyFastaValidation(name, value);
		} 
		
		
		/*
		 * CLUSTAL format validation
		 */
		else if( isFormatClustal(format) && Utils.isNotEmpty(value) ) 
		{
			error = applyClustalValidation(name, value);
		} 	
		
		

		/*
		 * Script validation is executed only if no format error has been detected 
		 */
		if( script != null && error == null ) {
			error = applyScriptValidation(name,value);
		}

		return error;
	}



	private ErrorWrapper applyScriptValidation(String name, String value) {

		Service service = Service.current();
		if( service == null ) {
			Logger.warn("Cannot access current service. Skipping script validation");
			return null;
		}
		
		/*
		 * pass the field under validation
		 */
		script.setProperty("field", service.input.field(name));

		/*
		 * pass the value to be validated 
		 */
		script.setProperty("value", getTypedValue(value));
		
		/*
		 * invoke the script
		 */
		Object result = script.run().getResult();
		
		/*
		 * In the value is changed by the script, it is interpreted as a normalization process 
		 */
		Object retvalue = script.getProperty("value");
		if( retvalue != null && retvalue != value ) {
			fNormalizedValue = getStringValue(retvalue);
		}
		
		/*
		 * any object returned by the script is interpreted as an error message 
		 */
		return 	result != null ? error( name, result.toString(), new String[0]) : null;
	}


	private Object getStringValue(Object value) {
		if( value instanceof Date ) {
			return Utils.asString((Date)value);
		}

		return value.toString();
	}



	/**
	 * Convert a string to a 'primitive' type according to the declared format 
	 * 
	 * @param value
	 * @return
	 */
	protected Object getTypedValue(String value) {

		if( isFormatInteger(format) ) {
			return Utils.parseLong(value);
		}
		
		if( isFormatNumber(format) ) {
			return Utils.parseDouble(value);
		}
		
		if( isFormatDate(format) ) {
			return Utils.parseDate(value);
		}

		return value;
	}



	/**
	 * Apply the clustal validation
	 * @param name the field name
	 * @param value the field value
	 */
	ErrorWrapper applyClustalValidation(String name, String value) {
		Clustal clustal = new Clustal(type2alphabet(type));
		
		/* parse the sequences */
		clustal.parse(value);
		
		/* check for validity */
        if ( !clustal.isValid() ) { 
			String message = Utils.isNotEmpty(formatError) ? concat(formatError,clustal.getError()) : "validation.clustal.format";
        	return error(name, message, new String[] {clustal.getError()} );
        } 
        else if( minNum != null && clustal.count()<minNum ) { 
			String message = Utils.isNotEmpty(minNumError) ? minNumError : "validation.clustal.minum";
        	return error(name, message, new String[0]);
        }
        else if( maxNum != null && clustal.count()>maxNum ) { 
			String message = Utils.isNotEmpty(maxNumError) ? maxNumError : "validation.clustal.maxnum";
        	return error(name, message, new String[0]);
        }
        else if( minLength != null && clustal.minLength()<minLength ) { 
			String message = Utils.isNotEmpty(minLengthError) ? minLengthError : "validation.clustal.minlen";
        	return error(name, message, new String[0]);
        }
        else if( maxLength != null && clustal.maxLength()>maxLength ) { 
			String message = Utils.isNotEmpty(maxLengthError) ? maxLengthError : "validation.clustal.maxlen";
        	return error(name, message, new String[0]);
        }
        else if( sameLength && clustal.minLength() != clustal.maxLength() ) {
			String message = Utils.isNotEmpty(sameLengthError) ? sameLengthError : "validation.clustal.samelen";
        	return error(name, message, new String[0]);
        }
        
   
        // normalize the fasta sequence 
        fNormalizedValue = clustal.toString();
  
		return null;
	}


	private ErrorWrapper error(String fieldName, String message, String... variables) {
		ErrorWrapper result = new ErrorWrapper();
		result.fieldName = fieldName;
		result.message = message;
		result.variables = variables;
		return result;
	}

	static class ErrorWrapper implements Serializable { 
		String fieldName;
		String message;
		String[] variables;
	}

	/**
	 * Apply the FASTA format validation
	 * @param name the field name 
	 * @param value the field value
	 */
	ErrorWrapper applyFastaValidation(String name, String value) {
		Fasta fasta = new Fasta(type2alphabet(type));
		
		/* parse the sequences */
		fasta.parse(value);
		
		/* check for validity */
        if ( !fasta.isValid() ) { 
			String message = Utils.isNotEmpty(formatError) ? concat(formatError,fasta.getError()) : "validation.fasta.format";
        	return error(name, message, new String[] {fasta.getError()} );
        } 
        else if( minNum != null && fasta.count()<minNum ) { 
			String message = Utils.isNotEmpty(minNumError) ? minNumError : "validation.fasta.minum";
        	return error(name, message, new String[0]);
        }
        else if( maxNum != null && fasta.count()>maxNum ) { 
			String message = Utils.isNotEmpty(maxNumError) ? maxNumError : "validation.fasta.maxnum";
        	return error(name, message, new String[0]);
        }
        else if( minLength != null && fasta.minLength()<minLength ) { 
			String message = Utils.isNotEmpty(minLengthError) ? minLengthError : "validation.fasta.minlen";
        	return error(name, message, new String[0]);
        }
        else if( maxLength != null && fasta.maxLength()>maxLength ) { 
			String message = Utils.isNotEmpty(maxLengthError) ? maxLengthError : "validation.fasta.maxlen";
        	return error(name, message, new String[0]);
        }
        else if( sameLength && fasta.minLength() != fasta.maxLength() ) {
			String message = Utils.isNotEmpty(sameLengthError) ? sameLengthError : "validation.fasta.samelen";
        	return error(name, message, new String[0]);
        }
        
        // normalize the fasta sequence 
        fNormalizedValue = fasta.toString();
        
        // no error
        return null;
	}


	/**
	 * Apply the Decimal format validation 
	 * @param name the field value 
	 * @param value the field value
	 */
	ErrorWrapper applyDecimalValidation(String name, String value) {
		Double num = Utils.parseDouble(value,null);
		if( num == null ) {
			String message = Utils.isNotEmpty(formatError) ? formatError : "validation.decimal.format";
			return error(name, message, new String[] {value});			
		}
		
		Double min = getMinDecimal();
		if( min != null && num != null && num < min ) {
			String message = Utils.isNotEmpty(minError) ? minError : "validation.decimal.min";
			return error(name, message, new String[] {value});			
		}
		
		Double max = getMaxDecimal();
		if( max != null && num != null && num > max ) {
			String message = Utils.isNotEmpty(maxError) ? maxError : "validation.decimal.max";
			return error(name, message, new String[] {value});			
		}

		// no error 
		return null;
	}


	/**
	 * Apply the Integer format validation
	 * @param name the field name 
	 * @param value the field value
	 */
	ErrorWrapper applyIntegerValidation(String name, String value) {
		Integer num = Utils.parseInteger(value,null);
		if( num == null ) {
			String message = Utils.isNotEmpty(formatError) ? formatError : "validation.integer.format";
			return error(name, message, new String[] {value});			
		}
		
		Integer min = getMinAsInteger();
		if( min != null && num != null && num < min ) {
			String message = Utils.isNotEmpty(minError) ? minError : "validation.integer.min";
			return error(name, message, new String[] {value});			
		}
		
		Integer max = getMaxAsInteger();
		if( max != null && num != null && num > max ) {
			String message = Utils.isNotEmpty(maxError) ? maxError : "validation.integer.max";
			return error(name, message, new String[] {value});			
		}
		
		// no error 
		return null;
		
	}

	
	/**
	 * Apply the Date format validation 
	 * @param name the field name 
	 * @param value the field value 
	 */
	ErrorWrapper applyDateValidation(String name, String value) {
		
		Date date = Utils.parseDate(value);
		if( date == null ) {
			String message = Utils.isNotEmpty(formatError) ? formatError : "validation.date.format";
			return error(name, message, new String[] {value});			
		}
	
		Date min = getMinDate();
		if( date!=null && min!=null && date.getTime() < min.getTime()) {
			String message = Utils.isNotEmpty(minError) ? minError : "validation.date.min";
			return error(name, message, new String[] {value});			
		}

		Date max = getMaxDate();
		if( date!=null && max!=null && date.getTime() > max.getTime()) {
			String message = Utils.isNotEmpty(maxError) ? maxError : "validation.date.max";
			return error(name, message, new String[] {value});			
		}
		
		// no error 
		return null;
		
	}


	/** 
	 * Apply the EMAIL format validation 
	 * @param name the field name 
	 * @param value the field value
	 */
	ErrorWrapper applyEmailValidation(String name, String value) {
		
		/* the value string can contains multiple addresses separated by a comma or a semicolon 
		 * split the string to check email address syntax one-by-one 
		 */
		String sEmail = value != null ? value : "";
		sEmail = sEmail.replace(",", ";"); // <-- normalize the comma 
		String[] addresses = sEmail.split(";");
		for( String addr : addresses ) { 
			addr = addr.trim();
			boolean isValid = new EmailCheck().isSatisfied(null, addr, null, null);
			if( !isValid ) {
				String message = Utils.isNotEmpty(formatError) ? formatError : "validation.email.format";
				return error(name, message, new String[] {addr});
			}
		}
		
		/* optional 'min' and 'max' attribute can be entered to specify the numeber of accepted email addresses  */
		Integer min = getMinAsInteger();
		Integer max = getMaxAsInteger();
		
		if( min != null && ( addresses.length < min ) ) {
			String message = Utils.isNotEmpty(minError) ? minError : "validation.minSize";
			return error(name, message, new String[] {value});
		}
		
		if( max != null && ( addresses.length > max ) ) {
			String message = Utils.isNotEmpty(maxError) ? maxError : "validation.maxSize";
			return error(name, message, new String[] {value});
		}
		
		// nornalize the email address 
		fNormalizedValue = value != null ? value.toLowerCase().trim() : null;
		
		// no error 
		return null;
		
	}


	/**
	 * Apply the TEXT format validation 
	 * @param name the field name 
	 * @param value the field value 
	 */
	ErrorWrapper applyTextValidation(String name, String value) {
		Integer min = getMinAsInteger();
		Integer max = getMaxAsInteger();
		if( min != null && ( value==null || value.trim().length()<min ) ) {
			String message = Utils.isNotEmpty(minError) ? minError : "validation.minSize";
			return error(name, message, new String[] {value});
		}
		
		if( max != null && ( value==null || value.trim().length()>max ) ) {
			String message = Utils.isNotEmpty(maxError) ? maxError : "validation.maxSize";
			return error(name, message, new String[] {value});
		}
		
		if( Utils.isNotEmpty(pattern) && !Pattern.matches(pattern, value) ) {
			String message = Utils.isNotEmpty(patternError) ? patternError : "validation.match";
			return error(name, message, new String[] {value});
		}

		// no error 
		return null;
		
	}


	/**
	 * Apply the REQUIRED constraint validation 
	 * @param name the field name 
	 * @param value the field value 
	 */
	ErrorWrapper applyRequiredValidation( String name, String value ) { 
		String message = Utils.isNotEmpty(requiredError) ? requiredError : "validation.required";
		return error(name, message, new String[] {name});		
	}
	
	/**
	 * Translate the string 'type' value to the matching {@link Alphabet} 
	 * 
	 * @param type one of the following values: <code>amino-acid</code>, <code>nucleic-acid</code>, <code>dna</code>, <code>rna</code>
	 * @return an instance of {@link Alphabet} corresponding to the specified string value, 
	 * if an unknown value is specified the default {@link Alphabet.AminoAcid} is returned 
	 */
	static Alphabet type2alphabet( String type ) { 
		if( Utils.isEmpty(type) || "amino-acid".equalsIgnoreCase(type) )  { 
			return Alphabet.AminoAcid.INSTANCE;
		}
		else if( "nucleic-acid".equalsIgnoreCase(type) ) { 
			 return Alphabet.NucleicAcid.INSTANCE;		
		}
		else if( "dna".equalsIgnoreCase(type) ) { 
			 return Alphabet.Dna.INSTANCE;		
		}
		else if( "rna".equalsIgnoreCase(type) ) { 
			 return Alphabet.Rna.INSTANCE;			
		}
		else { 
			Logger.warn("Unknown format type: '%s'", type);
			return Alphabet.AminoAcid.INSTANCE;
		}
	}
	
	
	/**
	 * Validator have the ability to normalize data to make them syntaxically correct  
	 * 
	 * @param <T> the value target type
	 * @return the nomalized value or <code>null</code> if no value is available;
	 */
	public <T> T getNormalizedValue() { 
		return (T) fNormalizedValue;
	}

	public boolean isValid() { 
		return fIsValid;
	}
	
	
	static String concat( String... args) { 
		StringBuilder result = new StringBuilder();
		if( args != null ) for( String part : args ) { 
			part = part.trim();
			if( Utils.isEmpty(part)) continue;
			if( !part.endsWith(".")) part += ".";
			if( result.length()>0 ) result.append(" ");
			result.append(part);
		}
		
		return result.toString();
	}
	
	public static boolean isFormatEmail(String format) {
		return "EMAIL".equalsIgnoreCase(format);
	}

	public static boolean isFormatDate(String format) {
		return "DATE".equalsIgnoreCase(format);
	}

	public static boolean isFormatNumber(String format) {
		return "DECIMAL".equalsIgnoreCase(format) || "NUMBER".equalsIgnoreCase(format);
	}

	public static boolean isFormatInteger(String format) {
		return "INTEGER".equalsIgnoreCase(format) || "INT".equalsIgnoreCase(format);
	}

	public static boolean isFormatText(String format) {
		return "TEXT".equalsIgnoreCase(format) ;
	}
	
	public static boolean isFormatClustal(String format) {
		return "CLUSTAL".equalsIgnoreCase(format) || "CLUSTALW".equalsIgnoreCase(format) ;
	}

	public static boolean isFormatFasta(String format) {
		return "FASTA".equalsIgnoreCase(format)  ;
	}

}
