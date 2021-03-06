/**
 * Copyright (c) 2014, the Railo Company Ltd.
 * Copyright (c) 2015, Lucee Assosication Switzerland
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either 
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public 
 * License along with this library.  If not, see <http://www.gnu.org/licenses/>.
 * 
 */
package lucee.commons.lang;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.UnmodifiableClassException;
import java.net.URL;
import java.util.Arrays;
import java.util.Map;

import lucee.commons.digest.HashUtil;
import lucee.commons.io.IOUtil;
import lucee.commons.io.SystemUtil;
import lucee.commons.io.res.Resource;
import lucee.commons.io.res.util.ResourceClassLoader;
import lucee.commons.io.res.util.ResourceUtil;
import lucee.runtime.config.Config;
import lucee.runtime.config.ConfigImpl;
import lucee.runtime.instrumentation.InstrumentationFactory;
import lucee.runtime.type.util.ArrayUtil;

import org.apache.commons.collections4.map.ReferenceMap;


/**
 * Directory ClassLoader
 */
public final class PhysicalClassLoader extends ExtendableClassLoader {
	
	private Resource directory;
	private ConfigImpl config; 
	private ClassLoader[] parents;
	
	private Map<String,PhysicalClassLoader> customCLs;
	
	/**
	 * Constructor of the class
	 * @param directory
	 * @param parent
	 * @throws IOException
	 */
	public PhysicalClassLoader(Config c,Resource directory) throws IOException {
		this(c,directory,null);
	}
	
	private PhysicalClassLoader(Config c,Resource directory, ResourceClassLoader rcl) throws IOException {
		super(rcl==null?c.getClassLoader():rcl);
		
		config = (ConfigImpl)c;

		ResourceClassLoader resCL = rcl!=null?rcl:config.getResourceClassLoader(null);
		ClassLoader coreCL = config.getClassLoaderCore();
		// ClassLoader loaderCL = config.getClassLoaderLoader(); this gives a wrong result, boot dekegation needs to handle this
		parents=resCL!=null?
				new ClassLoader[]{resCL,coreCL}:
				new ClassLoader[]{coreCL};
		
		
		
		// check directory
		if(!directory.exists())
			directory.mkdirs();
		if(!directory.isDirectory())
			throw new IOException("resource "+directory+" is not a directory");
		if(!directory.canRead())
			throw new IOException("no access to "+directory+" directory");
		this.directory=directory;
	}

	@Override
	public Class<?> loadClass(String name) throws ClassNotFoundException   {
		return loadClass(name, false);
	}

	@Override
	protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		// First, check if the class has already been loaded
		Class<?> c = findLoadedClass(name);
		//print.o("load:"+name+" -> "+c);
		if (c == null) {
			for(ClassLoader p:parents) {
				try {
					c = p.loadClass(name);
					break;
				} 
				catch (Throwable t) {}
			}
			if(c==null)c = findClass(name);
		}
		if (resolve)resolveClass(c);
		return c;
   }

	@Override
	protected Class<?> findClass(String name) throws ClassNotFoundException {//if(name.indexOf("sub")!=-1)print.ds(name);
		Resource res=directory
		.getRealResource(
				name.replace('.','/')
				.concat(".class"));
		
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			IOUtil.copy(res,baos,false);
		} 
		catch (IOException e) {
			throw new ClassNotFoundException("class "+name+" is invalid or doesn't exist");
		}
		
		byte[] barr=baos.toByteArray();
		IOUtil.closeEL(baos);
		return _loadClass(name, barr);
	}
	

	@Override
	public synchronized Class<?> loadClass(String name, byte[] barr) throws UnmodifiableClassException {
		Class<?> clazz=null;
		try {
			clazz = loadClass(name);
		} catch (ClassNotFoundException cnf) {}
		
		// if class already exists
		if(clazz!=null) {
			try {
				InstrumentationFactory.getInstrumentation(config).redefineClasses(new ClassDefinition(clazz,barr));
			} 
			catch (ClassNotFoundException e) {
				// the documentation clearly sais that this exception only exists for backward compatibility and never happen
			}
			return clazz;
		}
		// class not exists yet
		return _loadClass(name, barr);
	}
	
	private synchronized Class<?> _loadClass(String name, byte[] barr) {
		
		// class not exists yet
		try {
			return defineClass(name,barr,0,barr.length);
		} 
		catch (Throwable t) {
			SystemUtil.sleep(1);
			try {
				return defineClass(name,barr,0,barr.length);
			} 
			catch (Throwable t2) {
				SystemUtil.sleep(1);
				return defineClass(name,barr,0,barr.length);
			}
		}
	}
	
	
	
	@Override
	public URL getResource(String name) {
		/*URL url=super.getResource(name);
		if(url!=null) return url;
		
		Resource f =_getResource(name);
		if(f!=null) {
			try {
				return f.toURL();
			} 
			catch (MalformedURLException e) {}
		}*/
		return null;
	}

	@Override
	public InputStream getResourceAsStream(String name) {
		InputStream is = super.getResourceAsStream(name);
		if(is!=null) return is;
		
		Resource f = _getResource(name);
		if(f!=null)  {
			try {
				return IOUtil.toBufferedInputStream(f.getInputStream());
			} 
			catch (IOException e) {}
		}
		return null;
	}

	/**
	 * returns matching File Object or null if file not exust
	 * @param name
	 * @return matching file
	 */
	public Resource _getResource(String name) {
		Resource f = directory.getRealResource(name);
		if(f!=null && f.exists() && f.isFile()) return f;
		return null;
	}

	public boolean hasClass(String className) {
		return hasResource(className.replace('.','/').concat(".class"));
	}
	
	public boolean isClassLoaded(String className) {
		//print.o("isClassLoaded:"+className+"-"+(findLoadedClass(className)!=null));
		return findLoadedClass(className)!=null;
	}

	public boolean hasResource(String name) {
		return _getResource(name)!=null;
	}

	/**
	 * @return the directory
	 */
	public Resource getDirectory() {
		return directory;
	}
	
	public PhysicalClassLoader getCustomClassLoader(Resource[] resources, boolean reload) throws IOException{
		if(ArrayUtil.isEmpty(resources)) return this;
		String key = hash(resources);
		
		if(reload && customCLs!=null) customCLs.remove(key);
		
		
		PhysicalClassLoader pcl=customCLs==null?null:customCLs.get(key);
		if(pcl!=null) return pcl; 
		pcl=new PhysicalClassLoader(config,getDirectory(),new ResourceClassLoader(resources,getParent()));
		if(customCLs==null)customCLs=new ReferenceMap<String,PhysicalClassLoader>();
		customCLs.put(key, pcl);
		return pcl;
	}
	
	private String hash(Resource[] resources) {
		Arrays.sort(resources);
		StringBuilder sb=new StringBuilder();
		for(int i=0;i<resources.length;i++){
			sb.append(ResourceUtil.getCanonicalPathEL(resources[i]));
			sb.append(';');
		}
		return HashUtil.create64BitHashAsString(sb.toString(),Character.MAX_RADIX);
	}
}