package org.jscookie;

import java.io.CharArrayWriter;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class Cookies implements CookiesDefinition {
	private HttpServletRequest request;
	private HttpServletResponse response;
	private AttributesDefinition defaults = Attributes.empty();
	private ConverterStrategy converter;
	private ObjectMapper mapper = new ObjectMapper();

	private static final String LSTRING_FILE = "javax.servlet.http.LocalStrings";
	private static ResourceBundle lStrings = ResourceBundle.getBundle( LSTRING_FILE );

	private Cookies( HttpServletRequest request, HttpServletResponse response, ConverterStrategy converter ) {
		this.request = request;
		this.response = response;
		this.converter = converter;
	}

	public static Cookies initFromServlet( HttpServletRequest request, HttpServletResponse response ) {
		return new Cookies( request, response, null );
	}

	@Override
	public synchronized String get( String name ) {
		if ( name == null || name.length() == 0 ) {
			throw new IllegalArgumentException( lStrings.getString( "err.cookie_name_blank" ) );
		}

		String cookieHeader = request.getHeader( "cookie" );
		if ( cookieHeader == null ) {
			return null;
		}

		Map<String, String> cookies = getCookies( cookieHeader );
		for ( String decodedName : cookies.keySet() ) {
			if ( !name.equals( decodedName ) ) {
				continue;
			}
			return cookies.get( decodedName );
		}

		return null;
	}

	@Override
	public <T> T get( String name, Class<T> dataType ) throws CookieParseException {
		String value = get( name );
		try {
			return mapper.readValue( value, dataType );
		} catch ( IOException e ) {
			throw new CookieParseException( e );
		}
	}

	@Override
	public <T> T get( String name, TypeReference<T> typeRef ) throws CookieParseException {
		String value = get( name );
		try {
			return mapper.readValue( value, typeRef );
		} catch ( IOException e ) {
			throw new CookieParseException( e );
		}
	}

	@Override
	public Map<String, String> get() {
		Map<String, String> result = new HashMap<String, String>();

		String cookieHeader = request.getHeader( "cookie" );
		if ( cookieHeader == null ) {
			return result;
		}

		return getCookies( cookieHeader );
	}

	@Override
	public synchronized void set( String name, String value, AttributesDefinition attributes ) {
		if ( name == null || name.length() == 0 ) {
			throw new IllegalArgumentException( lStrings.getString( "err.cookie_name_blank" ) );
		}
		if ( value == null ) {
			throw new IllegalArgumentException();
		}
		if ( attributes == null ) {
			throw new IllegalArgumentException();
		}

		String encodedName = encode( name );
		String encodedValue = encodeValue( value );

		StringBuilder header = new StringBuilder();
		header.append( encodedName );
		header.append( '=' );
		header.append( encodedValue );

		attributes = extend( Attributes.empty().path( "/" ), defaults, attributes );

		Expiration expires = attributes.expires();
		if ( expires != null ) {
			// TODO
		}

		String path = attributes.path();
		if ( path != null && !path.isEmpty() ) {
			header.append( "; Path=" + path );
		}

		String domain = attributes.domain();
		if ( domain != null ) {
			header.append( "; Domain=" + domain );
		}

		Boolean secure = attributes.secure();
		if ( Boolean.TRUE.equals( secure ) ) {
			header.append( "; Secure" );
		}

		if ( response.isCommitted() ) {
			return;
		}

		response.addHeader( "Set-Cookie", header.toString() );
	}

	@Override
	public void set( String name, int value, AttributesDefinition attributes ) throws CookieSerializationException {
		set( name, String.valueOf( value ), attributes );
	}

	@Override
	public void set( String name, boolean value, AttributesDefinition attributes ) throws CookieSerializationException {
		set( name, String.valueOf( value ), attributes );
	}

	@Override
	public <T> void set( String name, List<T> value, AttributesDefinition attributes ) throws CookieSerializationException {
		try {
			set( name, mapper.writeValueAsString( value ), attributes );
		} catch ( JsonProcessingException e ) {
			throw new CookieSerializationException( e );
		}
	}

	@Override
	public void set( String name, CookieValue value, AttributesDefinition attributes ) throws CookieSerializationException {
		try {
			set( name, mapper.writeValueAsString( value ), attributes );
		} catch ( JsonProcessingException e ) {
			throw new CookieSerializationException( e );
		}
	}

	@Override
	public void set( String name, String value ) {
		if ( name == null || name.length() == 0 ) {
			throw new IllegalArgumentException( lStrings.getString( "err.cookie_name_blank" ) );
		}
		if ( value == null ) {
			throw new IllegalArgumentException();
		}
		set( name, value, defaults );
	}

	@Override
	public void set( String name, int value ) throws CookieSerializationException {
		set( name, value, Attributes.empty() );
	}

	@Override
	public void set( String name, boolean value ) {
		set( name, String.valueOf( value ) );
	}

	@Override
	public <T> void set( String name, List<T> value ) throws CookieSerializationException {
		set( name, value, Attributes.empty() );
	}

	@Override
	public void set( String name, CookieValue value ) throws CookieSerializationException {
		set( name, value, Attributes.empty() );
	}

	@Override
	public void remove( String name, AttributesDefinition attributes ) {
		if ( name == null || name.length() == 0 ) {
			throw new IllegalArgumentException( lStrings.getString( "err.cookie_name_blank" ) );
		}
		if ( attributes == null ) {
			throw new IllegalArgumentException();
		}

		set( name, "", extend( attributes, Attributes.empty()
			.expires( Expiration.days( -1 ) ))
		);
	}

	@Override
	public void remove( String name ) {
		if ( name == null || name.length() == 0 ) {
			throw new IllegalArgumentException( lStrings.getString( "err.cookie_name_blank" ) );
		}
		remove( name, Attributes.empty() );
	}

	@Override
	public AttributesDefinition defaults() {
		return this.defaults;
	}

	@Override
	public Cookies withConverter( ConverterStrategy converter ) {
		return new Cookies( request, response, converter );
	}

	private Attributes extend( AttributesDefinition... mergeables ) {
		Attributes result = Attributes.empty();
		for ( AttributesDefinition mergeable : mergeables ) {
			result.merge( mergeable );
		}
		return result;
	}

	private String encode( String decoded ) {
		return encode( decoded, new HashSet<Character>() );
	}

	private String encode( String decoded, Set<Character> exceptions ) {
		String encoded = decoded;
		for ( int i = 0; i < decoded.length(); i++ ) {
			Character character = decoded.charAt( i );

			boolean isDigit = Character.isDigit( character );
			if ( isDigit ) {
				continue;
			}

			boolean isAsciiUppercaseLetter = character >= 'A' && character <= 'Z';
			if ( isAsciiUppercaseLetter ) {
				continue;
			}

			boolean isAsciiLowercaseLetter = character >= 'a' && character <= 'z';
			if ( isAsciiLowercaseLetter ) {
				continue;
			}

			boolean isAllowed =
					character == '!' || character == '#'  || character == '$' ||
					character == '&' || character == '\'' || character == '*' ||
					character == '+' || character == '-'  || character == '.' ||
					character == '^' || character == '_'  || character == '`' ||
					character == '|' || character == '~';
			if ( isAllowed ) {
				continue;
			}

			if ( exceptions.contains( character ) ) {
				continue;
			}

			try {
				CharArrayWriter hexSequence = new CharArrayWriter();
				byte[] bytes = character.toString().getBytes( StandardCharsets.UTF_8.name() );
				for ( int bytesIndex = 0; bytesIndex < bytes.length; bytesIndex++ ) {
					char left = Character.forDigit( bytes[ bytesIndex ] >> 4 & 0xF, 16 );
					char right = Character.forDigit( bytes[ bytesIndex ] & 0xF, 16 );
					hexSequence
						.append( '%' )
						.append( left )
						.append( right );
				}
				String target = character.toString();
				String sequence = hexSequence.toString().toUpperCase();
				encoded = encoded.replace( target, sequence );
			} catch ( UnsupportedEncodingException e ) {
				e.printStackTrace();
			}
		}
		return encoded;
	}

	private String decode( String encoded ) {
		String decoded = encoded;
		Pattern pattern = Pattern.compile( "(%[0-9A-Z]{2})+" );
		Matcher matcher = pattern.matcher( encoded );
		while ( matcher.find() ) {
			String encodedChar = matcher.group();
			String[] encodedBytes = encodedChar.split( "%" );
			byte[] bytes = new byte[ encodedBytes.length - 1 ];
			for ( int i = 1; i < encodedBytes.length; i++ ) {
				String encodedByte = encodedBytes[ i ];
				bytes[ i - 1 ] = ( byte )Integer.parseInt( encodedByte, 16 );
			}
			try {
				String decodedChar = new String( bytes, StandardCharsets.UTF_8.toString() );
				decoded = decoded.replace( encodedChar, decodedChar );
			} catch ( UnsupportedEncodingException e ) {
				e.printStackTrace();
			}
		}
		return decoded;
	}

	private String encodeValue( String decodedValue ) {
		Set<Character> exceptions = new HashSet<>();
		for ( int i = 0; i < decodedValue.length(); i++ ) {
			char character = decodedValue.charAt( i );
			boolean isIgnorable = false;

			if ( character == '/' || character == ':' ) {
				isIgnorable = true;
			}

			if ( character >= '<' && character <= '@' ) {
				isIgnorable = true;
			}

			if ( character == '[' || character == ']' ) {
				isIgnorable = true;
			}

			if ( character == '{' || character == '}' ) {
				isIgnorable = true;
			}

			if ( isIgnorable ) {
				exceptions.add( character );
			}
		}

		return encode( decodedValue, exceptions );
	}

	private String decodeValue( String encodedValue, String decodedName ) {
		String decodedValue = null;

		if ( converter != null ) {
			try {
				decodedValue = converter.convert( encodedValue, decodedName );
			} catch ( ConverterException e ) {
				e.printStackTrace();
			}
		}

		if ( decodedValue == null ) {
			decodedValue = decode( encodedValue );
		}

		return decodedValue;
	}

	private Map<String, String> getCookies( String cookieHeader ) {
		Map<String, String> result = new HashMap<>();
		String[] cookies = cookieHeader.split( "; " );
		for ( int i = 0; i < cookies.length; i++ ) {
			String cookie = cookies[ i ];
			String encodedName = cookie.split( "=" )[ 0 ];
			String decodedName = decode( encodedName );

			String encodedValue = cookie.substring( cookie.indexOf( '=' ) + 1, cookie.length() );
			String decodedValue = decodeValue( encodedValue, decodedName );
			result.put( decodedName, decodedValue );
		}
		return result;
	}

	public static class Attributes extends AttributesDefinition {
		private Expiration expires;
		private String path;
		private String domain;
		private Boolean secure;

		private Attributes() {}

		public static Attributes empty() {
			return new Attributes();
		}

		@Override
		Expiration expires() {
			return expires;
		}
		@Override
		public Attributes expires( Expiration expires ) {
			this.expires = expires;
			return this;
		}

		@Override
		String path() {
			return path;
		}
		@Override
		public Attributes path( String path ) {
			this.path = path;
			return this;
		}

		@Override
		String domain() {
			return domain;
		}
		@Override
		public Attributes domain( String domain ) {
			this.domain = domain;
			return this;
		}

		@Override
		Boolean secure() {
			return secure;
		}
		@Override
		public Attributes secure( Boolean secure ) {
			this.secure = secure;
			return this;
		}

		private Attributes merge( AttributesDefinition reference ) {
			if ( reference.path() != null ) {
				path = reference.path();
			}
			if ( reference.domain() != null ) {
				domain = reference.domain();
			}
			if ( reference.expires() != null ) {
				expires = reference.expires();
			}
			if ( reference.secure() != null ) {
				secure = reference.secure();
			}
			return this;
		}
	}

	public static abstract class Converter implements ConverterStrategy {}
}
