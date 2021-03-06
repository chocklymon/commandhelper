
package com.laytonsmith.PureUtilities.ClassLoading.ClassMirror;

import com.laytonsmith.PureUtilities.ClassLoading.ClassDiscovery;
import com.laytonsmith.PureUtilities.Common.ClassUtils;
import com.laytonsmith.PureUtilities.Common.StringUtils;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * This class gathers information about a class, without actually loading 
 * the class into memory. Most of the methods in {@link java.lang.Class} are
 * available in this class (or have an equivalent Mirror version).
 * @param <T>
 */
public class ClassMirror<T> implements Serializable {
	private static final long serialVersionUID = 1L;
	private final ClassInfo info = new ClassInfo();
	//Transient, because it's only used during construction
	private final transient org.objectweb.asm.ClassReader reader;
	
	/**
	 * If this is just a wrapper for an already loaded Class, this will
	 * be non-null, and should override all the existing methods with
	 * the wrapped return.
	 */
	private final Class underlyingClass;
        
	/**
	 * The original URL that houses this class.
	**/
	private final URL originalURL;
	
	/**
	 * Creates a ClassMirror object for a given input stream representing
	 * a class file.
	 * @param is
	 * @param container
	 * @throws IOException 
	 */
	public ClassMirror(InputStream is, URL container) throws IOException {
		reader = new org.objectweb.asm.ClassReader(is);
		underlyingClass = null;
		originalURL = container;
		
		parse();
	}
	
	/**
	 * Creates a ClassMirror object for a given class file.
	 * @param file
	 * @throws FileNotFoundException
	 * @throws IOException 
	 */
	public ClassMirror(File file) throws FileNotFoundException, IOException {
		this(new FileInputStream(file), file.toURI().toURL());
	}
	
	/**
	 * Creates a ClassMirror object from an already loaded Class. While this
	 * obviously defeats the purpose of not loading the Class into PermGen, this
	 * does allow already loaded classes to fit into the ClassMirror ecosystem.
	 * Essentially, calls to the ClassMirror are simply forwarded to the Class and
	 * the return re-wrapped in sub mirror types. Some operations are not possible,
	 * namely non-runtime annotation processing, but in general, all other operations
	 * work the same.
	 * @param c 
	 */
	public ClassMirror(Class c){
		this.underlyingClass = c;
		reader = null;
		originalURL = ClassDiscovery.GetClassContainer(c);
	}
	
	private void parse(){
		reader.accept(info, org.objectweb.asm.ClassReader.SKIP_CODE 
				| org.objectweb.asm.ClassReader.SKIP_DEBUG 
				| org.objectweb.asm.ClassReader.SKIP_FRAMES);
	}
        
	/**
	 * Return the container that houses this class.
	 * @return 
	 */
	public URL getContainer() {
		return originalURL;
	}
	
	/**
	 * Returns the modifiers on this class.
	 * @return 
	 */
	public ModifierMirror getModifiers(){
		if(underlyingClass != null){
			return new ModifierMirror(underlyingClass.getModifiers());
		}
		return info.modifiers;
	}
	
	/**
	 * Returns the name of this class as recognized by the JVM, not the
	 * common class name. Use {@link #getClassName()} instead, if you want
	 * the common name.
	 * @return 
	 */
	public String getJVMClassName(){
		if(underlyingClass != null){
			return ClassUtils.getJVMName(underlyingClass);
		}
		return "L" + info.name + ";";
	}
	
	/**
	 * Returns the class name of this class. This is the "normal" name, that
	 * is, what you would type in code to reference a class, without / or $.
	 * @return 
	 */
	public String getClassName(){
		if(underlyingClass != null){
			return underlyingClass.getName().replace("$", ".");
		}
		return info.name.replaceAll("[/$]", ".");
	}
	
	/**
	 * Returns true if this class is an enum
	 * @return 
	 */
	public boolean isEnum(){
		if(underlyingClass != null){
			return underlyingClass.isEnum();
		}
		return info.isEnum;
	}
	
	/**
	 * Returns true if this class is an interface.
	 * @return 
	 */
	public boolean isInterface(){
		if(underlyingClass != null){
			return underlyingClass.isInterface();
		}
		return info.isInterface;
	}
	
	/**
	 * Returns true iff the underlying class is an abstract class (not an interface).
	 * @return 
	 */
	public boolean isAbstract() {
		if(underlyingClass != null){
			return (underlyingClass.getModifiers() & Modifier.ABSTRACT) > 0;
		}
		return info.modifiers.isAbstract();
	}
	
	/**
	 * Returns a {@link ClassReferenceMirror} to the class's superclass.
	 * @return 
	 */
	public ClassReferenceMirror getSuperClass(){
		if(underlyingClass != null){
			return ClassReferenceMirror.fromClass(underlyingClass.getSuperclass());
		}
		return new ClassReferenceMirror("L" + info.superClass + ";");
	}
	
	/**
	 * Returns a list of {@link ClassReferenceMirror}s of all the interfaces that
	 * this implements.
	 * @return 
	 */
	public List<ClassReferenceMirror> getInterfaces(){
		List<ClassReferenceMirror> l = new ArrayList<>();
		if(underlyingClass != null){
			for(Class inter : underlyingClass.getInterfaces()){
				l.add(ClassReferenceMirror.fromClass(inter));
			}
		} else {
			for(String inter : info.interfaces){
				l.add(new ClassReferenceMirror("L" + inter + ";"));
			}
		}
		return l;
	}
	
	/**
	 * Returns true if this class contains the annotation specified. 
	 * @param annotation
	 * @return 
	 */
	public boolean hasAnnotation(Class<? extends Annotation> annotation){
		if(underlyingClass != null){
			return underlyingClass.getAnnotation(annotation) != null;
		}
		String name = ClassUtils.getJVMName(annotation);
		for(AnnotationMirror a : info.annotations){
			if(a.getType().getJVMName().equals(name)){
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Because ClassMirror works with annotations that were declared as
	 * either {@link RetentionPolicy#CLASS} or {@link RetentionPolicy#RUNTIME},
	 * you may also want to check visibility. If this returns false, then the
	 * class does have the annotation, but {@link Class#getAnnotation(java.lang.Class)}
	 * would return false. If the class doesn't have the annotation, null is returned.
	 * Note that if this ClassMirror was initialized from a loaded Class object, this
	 * may not return correct information, because it essentially will be returning
	 * the result of {@link #hasAnnotation(java.lang.Class)}, since there is no way
	 * to tell if an annotation is anything but runtime.
	 * @param annotation
	 * @return 
	 */
	public Boolean isAnnotationVisible(Class<? extends Annotation> annotation){
		if(underlyingClass != null){
			return hasAnnotation(annotation);
		}
		String name = ClassUtils.getJVMName(annotation);
		for(AnnotationMirror a : info.annotations){
			if(a.getType().getJVMName().equals(name)){
				return a.isVisible();
			}
		}
		return null;
	}
	
	/**
	 * Returns the annotation defined on this class.
	 * @param clazz
	 * @return 
	 */
	public AnnotationMirror getAnnotation(Class<? extends Annotation> clazz){
		if(underlyingClass != null){
			Annotation ann = underlyingClass.getAnnotation(clazz);
			if(ann == null){
				return null;
			}
			return new AnnotationMirror(ann);
		}
		String name = ClassUtils.getJVMName(clazz);
		for(AnnotationMirror a : info.annotations){
			if(a.getType().getJVMName().equals(name)){
				return a;
			}
		}
		return null;
	}
	
	/**
	 * Returns a list of annotations on this class.
	 * @return 
	 */
	public List<AnnotationMirror> getAnnotations(){
		if(underlyingClass != null){
			List<AnnotationMirror> list = new ArrayList<>();
			for(Annotation a : underlyingClass.getAnnotations()){
				list.add(new AnnotationMirror(ClassReferenceMirror.fromClass(a.annotationType()), true));
			}
			return list;
		}
		return new ArrayList<>(info.annotations);
	}
	
	/**
	 * Loads the corresponding Annotation type for this field
	 * or method. This actually loads the Annotation class into memory.
	 * This is equivalent to getAnnotation(type).getProxy(type), however
	 * this checks for null first, and returns null instead of causing a NPE.
	 * In the case that this is a wrapper for a real Class object, this simply
	 * returns the real Annotation object (or null).
	 * @param <T>
	 * @param type
	 * @return 
	 */
	public <T extends Annotation> T loadAnnotation(Class<T> type) {
		if(underlyingClass != null){
			return (T) underlyingClass.getAnnotation(type);
		}
		AnnotationMirror mirror = getAnnotation(type);
		if(mirror == null){
			return null;
		}
		return mirror.getProxy(type);
	}
	
	/**
	 * Returns the fields in this class. This works like 
	 * {@link Class#getDeclaredFields()}, as only the methods in
	 * this class are loaded.
	 * @return 
	 */
	public FieldMirror[] getFields(){
		if(underlyingClass != null){
			FieldMirror[] fields = new FieldMirror[this.underlyingClass.getDeclaredFields().length];
			for(int i = 0; i < fields.length; i++){
				Field f = this.underlyingClass.getDeclaredFields()[i];
				fields[i] = new FieldMirror(f);
			}
			return fields;
		}
		return info.fields.toArray(new FieldMirror[info.fields.size()]);
	}
	
	/**
	 * Returns the field, given by name. This does not traverse the
	 * Object hierarchy, unlike {@link Class#getField(java.lang.String)}.
	 * @param name
	 * @return 
	 * @throws java.lang.NoSuchFieldException 
	 */
	public FieldMirror getField(String name) throws NoSuchFieldException {
		for(FieldMirror m : getFields()){
			if(m.getName().equals(name)){
				return m;
			}
		}
		throw new NoSuchFieldException("The field \"" + name + "\" was not found.");
	}
	
	/**
	 * Returns the methods in this class. This traverses the parent Object
	 * heirarchy if the methods are apart of the visible interface, as well as
	 * private methods in this class itself.
	 * @return 
	 */
	public MethodMirror[] getMethods(){
		if(underlyingClass != null){
			MethodMirror[] mirrors = new MethodMirror[underlyingClass.getDeclaredMethods().length];
			for(int i = 0; i < mirrors.length; i++){
				mirrors[i] = new MethodMirror(underlyingClass.getDeclaredMethods()[i]);
			}
			return mirrors;
		}
		return info.methods.toArray(new MethodMirror[info.methods.size()]);
	}
	
	/**
	 * Returns the method, given by name. This traverses the parent Object heirarchy
	 * if the methods are apart of the visible interface, as well as private methods
	 * in this class itself.
	 * @param name
	 * @param params
	 * @return 
	 * @throws java.lang.NoSuchMethodException 
	 */
	public MethodMirror getMethod(String name, Class...params) throws NoSuchMethodException{
		ClassReferenceMirror mm [] = new ClassReferenceMirror[params.length];
		for(int i = 0; i < params.length; i++){
			mm[i] = new ClassReferenceMirror(ClassUtils.getJVMName(params[i]));
		}
		return getMethod(name, mm);
	}
	
	/**
	 * Returns the method, given by name. This traverses the parent Object heirarchy
	 * if the methods are apart of the visible interface, as well as private methods
	 * in this class itself.
	 * @param name
	 * @param params
	 * @return
	 * @throws NoSuchMethodException 
	 */
	public MethodMirror getMethod(String name, ClassReferenceMirror... params) throws NoSuchMethodException {
		List<ClassReferenceMirror> crmParams = new ArrayList<>();
		crmParams.addAll(Arrays.asList(params));
		for(MethodMirror m : getMethods()){
			if(m.getName().equals(name) && m.getParams().equals(crmParams)){
				return m;
			}
		}
		throw new NoSuchMethodException("No method matching the signature " + name + "(" + StringUtils.Join(crmParams, ", ") + ") was found.");
		
	}
	
	/**
	 * Loads the class into memory and returns the class object. For this
	 * call to succeed, the class must otherwise be on the class path. The standard
	 * class loader is used, and the class is initialized. If this is a wrapper
	 * for an already loaded Class object, that object is simply returned.
	 * @return 
	 */
	public Class<T> loadClass() throws NoClassDefFoundError {
		if(underlyingClass != null){
			return underlyingClass;
		}
		try{
			return (Class<T>)info.classReferenceMirror.loadClass();
		} catch(ClassNotFoundException ex){
			throw new NoClassDefFoundError();
		}
	}
	
	/**
	 * Loads the class into memory and returns the class object. For this
	 * call to succeed, the classloader specified must be able to find the class.
	 * If this is a wrapper for an already loaded Class object, that object is simply
	 * returned.
	 * @param loader
	 * @param initialize
	 * @return 
	 */
	public Class<T> loadClass(ClassLoader loader, boolean initialize) throws NoClassDefFoundError {
		if(underlyingClass != null){
			return underlyingClass;
		}
		try{
			return info.classReferenceMirror.loadClass(loader, initialize);
		} catch(ClassNotFoundException ex){
			throw new NoClassDefFoundError(ex.getMessage());
		}
	}
	
	/**
	 * Returns true if this class either extends or implements the class
	 * specified, or is the same as that class. Note that if it transiently
	 * extends from this class, it can't necessarily find that information without
	 * actually loading the intermediate class, so this is a less useful method
	 * than {@link Class#isAssignableFrom(java.lang.Class)}, however, in combination
	 * with a system that is aware of all classes in a class ecosystem, this can
	 * be used to piece together that information without actually loading the
	 * classes.
	 * @param superClass
	 * @return 
	 */
	public boolean directlyExtendsFrom(Class superClass){
		if(underlyingClass != null){
			return (underlyingClass.getSuperclass() == superClass);
		}
		String name = superClass.getName().replace(".", "/");
		if(info.superClass.equals(name)){
			return true;
		}
		for(String in : info.interfaces){
			if(in.equals(name)){
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Returns the Package this class is in. If this is not in a package,
	 * null is returned.
	 * @return 
	 */
	public PackageMirror getPackage(){
		if(underlyingClass != null){
			return new PackageMirror(underlyingClass.getPackage().getName());
		}
		String[] split = getClassName().split("\\.");
		if(split.length == 1){
			return null;
		}
		StringBuilder b = new StringBuilder();
		for(int i = 0; i < split.length - 1; i++){
			if(i != 0){
				b.append(".");
			}
			b.append(split[i]);
		}
		return new PackageMirror(b.toString());
	}
	
	/**
	 * Returns the simple name of this class. I.e. for java.lang.String, "String" is
	 * returned.
	 * @return 
	 */
	public String getSimpleName(){
		if(underlyingClass != null){
			return underlyingClass.getSimpleName();
		}
		String[] split = getClassName().split("\\.");
		return split[split.length - 1];
	}

	/**
	 * Returns a string representation of this object. The string will match the toString
	 * that would be generated by that of the Class object.
	 * @return 
	 */
	@Override
	public String toString() {
		return (isInterface()?
				"interface":
				(isEnum()?"enum":"class")) 
				+ " " + getClassName();
	}

	/**
	 * Returns a {@link ClassReferenceMirror} to the object. This is useful
	 * for classes that may not exist, as it doesn't require an actual known
	 * reference to the class to exist.
	 * @return 
	 */
	public ClassReferenceMirror getClassReference() {
		if(underlyingClass != null){
			return ClassReferenceMirror.fromClass(underlyingClass);
		}
		return new ClassReferenceMirror(getJVMClassName());
	}


	@Override
	public int hashCode() {
		int hash = 5;
		hash = 97 * hash + Objects.hashCode(this.getJVMClassName());
		return hash;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		final ClassMirror<?> other = (ClassMirror<?>) obj;
		return Objects.equals(this.getJVMClassName(), other.getJVMClassName());
	}

	
	private static class ClassInfo extends ClassVisitor implements Serializable {
		private static final long serialVersionUID = 1L;
		
		public ModifierMirror modifiers;
		public String name;
		public String superClass;
		public String[] interfaces;
		public List<AnnotationMirror> annotations = new ArrayList<>();
		public boolean isInterface = false;
		public boolean isEnum = false;
		public ClassReferenceMirror classReferenceMirror;
		public List<FieldMirror> fields = new ArrayList<>();
		public List<MethodMirror> methods = new ArrayList<>();

		public ClassInfo() {
			super(Opcodes.ASM4);
		}

		@Override
		public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
			if((access & Opcodes.ACC_ENUM) > 0){
				isEnum = true;
			}
			if((access & Opcodes.ACC_INTERFACE) > 0){
				isInterface = true;
			}
			this.modifiers = new ModifierMirror(ModifierMirror.Type.CLASS, access);
			this.name = name;
			//We know we aren't an array or a primitive, so we just add L...; to make
			//the binary name, which is what ClassReferenceMirror expects.
			this.classReferenceMirror = new ClassReferenceMirror("L" + name + ";");
			this.superClass = superName;
			this.interfaces = interfaces;
		}

		@Override
		public void visitSource(String source, String debug) {
			//Ignored
		}

		@Override
		public void visitOuterClass(String owner, String name, String desc) {
			//Ignored
		}

		@Override
		public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
			AnnotationMirror am = new AnnotationMirror(new ClassReferenceMirror(desc), visible);
			annotations.add(am);
			return new AnnotationV(am);
		}

		@Override
		public void visitAttribute(Attribute attr) {
			
		}

		@Override
		public void visitInnerClass(String name, String outerName, String innerName, int access) {
			
		}

		@Override
		public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
			final FieldMirror fm = new FieldMirror(classReferenceMirror, new ModifierMirror(ModifierMirror.Type.FIELD, access),
					new ClassReferenceMirror(desc), name, value);
			fields.add(fm);
			return new FieldVisitor(Opcodes.ASM4) {
			

				@Override
				public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
					AnnotationMirror m = new AnnotationMirror(new ClassReferenceMirror(desc), visible);
					fm.addAnnotation(m);
					return new AnnotationV(m);
				}

				@Override
				public void visitAttribute(Attribute attr) {
					
				}

				@Override
				public void visitEnd() {
					
				}
			};
		}
		
		private static transient final Pattern METHOD_SIGNATURE_PATTERN = Pattern.compile("^\\((.*)\\)(.*)$");

		@Override
		public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
			if("<init>".equals(name) || "<clinit>".equals(name)){
				//For now, we aren't interested in constructors or static initializers
				return null;
			}
			
			Matcher m = METHOD_SIGNATURE_PATTERN.matcher(desc);
			if(!m.find()){
				//The desc type didn't match?
				throw new Error("No match found for " + desc);
			}
			String inner = m.group(1);
			String ret = m.group(2);
			List<ClassReferenceMirror> params = new ArrayList<ClassReferenceMirror>();
			//Parsing the params list is a bit more complicated than it should be.
			StringBuilder b = new StringBuilder();
			boolean inObject = false;
			for(char c : inner.toCharArray()){
				b.append(c);
				if(inObject){
					if(c == ';'){
						inObject = false;
						params.add(new ClassReferenceMirror(b.toString()));
						b = new StringBuilder();
					}
				} else {
					if(c == 'L'){
						inObject = true;
					} else if(c != '['){
						params.add(new ClassReferenceMirror(b.toString()));
						b = new StringBuilder();
					} //otherwise, it's an array, continue.
				}
			}
			final MethodMirror mm = new MethodMirror(classReferenceMirror, new ModifierMirror(ModifierMirror.Type.METHOD, access),
					new ClassReferenceMirror(ret), name, params, 
					(access & Opcodes.ACC_VARARGS) > 0, (access & Opcodes.ACC_SYNTHETIC) > 0);
			methods.add(mm);
			return new MethodVisitor(Opcodes.ASM4) {

				@Override
				public AnnotationVisitor visitAnnotationDefault() {
					return null;
				}

				@Override
				public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
					AnnotationMirror am = new AnnotationMirror(new ClassReferenceMirror(desc), visible);
					mm.addAnnotation(am);
					return new AnnotationV(am);
				}

				@Override
				public AnnotationVisitor visitParameterAnnotation(int parameter, String desc, boolean visible) {
					return null;
				}

				@Override
				public void visitAttribute(Attribute attr) {
					
				}

				@Override
				public void visitCode() {
					
				}

				@Override
				public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
					
				}

				@Override
				public void visitInsn(int opcode) {
					
				}

				@Override
				public void visitIntInsn(int opcode, int operand) {
					
				}

				@Override
				public void visitVarInsn(int opcode, int var) {
					
				}

				@Override
				public void visitTypeInsn(int opcode, String type) {
					
				}

				@Override
				public void visitFieldInsn(int opcode, String owner, String name, String desc) {
					
				}

				@Override
				public void visitMethodInsn(int opcode, String owner, String name, String desc) {
					
				}

				@Override
				public void visitJumpInsn(int opcode, Label label) {
					
				}

				@Override
				public void visitLabel(Label label) {
					
				}

				@Override
				public void visitLdcInsn(Object cst) {
					
				}

				@Override
				public void visitIincInsn(int var, int increment) {
					
				}

				@Override
				public void visitTableSwitchInsn(int min, int max, Label dflt, Label[] labels) {
					
				}

				@Override
				public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
					
				}

				@Override
				public void visitMultiANewArrayInsn(String desc, int dims) {
					
				}

				@Override
				public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
					
				}

				@Override
				public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
					
				}

				@Override
				public void visitLineNumber(int line, Label start) {
					
				}

				@Override
				public void visitMaxs(int maxStack, int maxLocals) {
					
				}

				@Override
				public void visitEnd() {
					
				}
			};
		}

		@Override
		public void visitEnd() {
			
		}
		
	}
	
	private static class AnnotationV extends AnnotationVisitor {
		
		private final AnnotationMirror mirror;
		public AnnotationV(AnnotationMirror mirror){
			super(Opcodes.ASM4);
			this.mirror = mirror;
		}

		@Override
		public void visit(String name, Object value) {
			if(value instanceof org.objectweb.asm.Type){
				//Type can't serialize, so we need to store a reference to it.
				//This will only happen if it's a class type, so a ClassReferenceMirror
				//is what we need anyways.
				org.objectweb.asm.Type type = (org.objectweb.asm.Type) value;
				value = new ClassReferenceMirror(type.getDescriptor());
			}
			mirror.addAnnotationValue(name, value);
		}

		@Override
		public void visitEnum(String name, String desc, String value) {
			
		}

		@Override
		public AnnotationVisitor visitAnnotation(String name, String desc) {
			return null;
		}

		@Override
		public AnnotationVisitor visitArray(String name) {
			return null;
		}

		@Override
		public void visitEnd() {
			
		}
		
	}
	
}
