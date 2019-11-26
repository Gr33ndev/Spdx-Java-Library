/**
 * Copyright (c) 2019 Source Auditor Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.spdx.library.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.spdx.library.DefaultModelStore;
import org.spdx.library.InvalidSPDXAnalysisException;
import org.spdx.library.SpdxConstants;
import org.spdx.library.model.license.ListedLicenses;
import org.spdx.storage.IModelStore;
import org.spdx.storage.IModelStore.IdType;
import org.spdx.storage.IModelStore.ModelUpdate;

/**
 * @author Gary O'Neall
 * 
 * Superclass for all SPDX model objects
 * 
 * Provides the primary interface to the storage class that access and stores the data for 
 * the model objects.
 * 
 * Each model object is in itself stateless.  All state is maintained in the Model Store.  
 * The Document URI uniquely identifies the document containing the model object.
 * 
 * The concrete classes are expected to implements getters for the model class properties which translate
 * into calls to the getTYPEPropertyValue where TYPE is the type of value to be returned and the property name
 * is passed as a parameter.
 * 
 * There are 2 methods of setting values:
 *   - call the setPropertyValue, clearValueList or addPropertyValueToList methods - this will call the modelStore and store the
 *     value immediately
 *   - Gather a list of updates by calling the updatePropertyValue, updateClearValueList, or updateAddPropertyValue
 *     methods.  These methods return a ModelUpdate which can be applied later by calling the <code>apply()</code> method.
 *     A convenience method <code>Write.applyUpdatesInOneTransaction</code> will perform all updates within
 *     a single transaction. This method may result in higher performance updates for some Model Store implementations.
 *     Note that none of the updates will be applied until the storage manager update method is invoked.
 *     
 * This class also handles the conversion of a ModelObject to and from a TypeValue for storage in the ModelStore.
 *
 */
public abstract class ModelObject implements SpdxConstants {

	private IModelStore modelStore;
	private String documentUri;
	private String id;

	/**
	 * Create a new Model Object using an Anonomous ID with the defualt store and default document URI
	 * @throws InvalidSPDXAnalysisException 
	 */
	public ModelObject() throws InvalidSPDXAnalysisException {
		this(DefaultModelStore.getDefaultModelStore().getNextId(IdType.Anonomous, DefaultModelStore.getDefaultDocumentUri()));
	}
	
	/**
	 * Open or create a model object with the default store and default document URI
	 * @param id ID for this object - must be unique within the SPDX document
	 * @throws InvalidSPDXAnalysisException 
	 */
	public ModelObject(String id) throws InvalidSPDXAnalysisException {
		this(DefaultModelStore.getDefaultModelStore(), DefaultModelStore.getDefaultDocumentUri(), id, true);
	}
	
	/**
	 * @param modelStore Storage for the model objects
	 * @param documentUri SPDX Document URI for a document associated with this model
	 * @param id ID for this object - must be unique within the SPDX document
	 * @param create - if true, the object will be created in the store if it is not already present
	 * @throws InvalidSPDXAnalysisException
	 */
	public ModelObject(IModelStore modelStore, String documentUri, String id, boolean create) throws InvalidSPDXAnalysisException {
		this.modelStore = modelStore;
		this.documentUri = documentUri;
		this.id = id;
		if (modelStore == null) {
			throw new InvalidSPDXAnalysisException("Missing required model store") ;
		}
		if (!modelStore.exists(documentUri, id)) {
			if (create) {
				modelStore.create(documentUri, id, getType());
			} else {
				throw new SpdxIdNotFoundException(id+" does not exist in document "+documentUri);
			}
		}
	}
	
	// Abstract methods that must be implemented in the subclasses
	/**
	 * @return The class name for this object.  Class names are defined in the constants file
	 */
	public abstract String getType();
	
	/**
	 * @return Any verification errors or warnings associated with this object
	 */
	public abstract List<String> verify();
	
	/**
	 * @return the Document URI for this object
	 */
	public String getDocumentUri() {
		return this.documentUri;
	}
	
	/**
	 * @return ID for the object
	 */
	public String getId() {
		return id;
	}
	
	/**
	 * @return the model store for this object
	 */
	public IModelStore getModelStore() {
		return this.modelStore;
	}
	
	//The following methods are to manage the properties associated with the model object
	/**
	 * @return all names of property values currently associated with this object
	 * @throws InvalidSPDXAnalysisException 
	 */
	public List<String> getPropertyValueNames() throws InvalidSPDXAnalysisException {
		return modelStore.getPropertyValueNames(documentUri, id);
	}
	
	/**
	 * @return all names of property lists currently associated with this object
	 * @throws InvalidSPDXAnalysisException 
	 */
	public List<String> getPropertyValueListNames() throws InvalidSPDXAnalysisException {
		return modelStore.getPropertyValueListNames(documentUri, id);
	}
	
	/**
	 * Get an object value for a property
	 * @param propertyName Name of the property
	 * @return value associated with a property
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public Optional<Object> getObjectPropertyValue(String propertyName) throws InvalidSPDXAnalysisException {
		Optional<Object> result =  modelStore.getValue(documentUri, id, propertyName);
		if (result.isPresent() && result.get() instanceof TypedValue) {
			TypedValue tv = (TypedValue)result.get();
			result = Optional.of(SpdxModelFactory.createModelObject(modelStore, this.documentUri, tv.getId(), tv.getType()));
		} else if (result.isPresent() && result.get() instanceof List) {
			List converted = new ArrayList();
			List lResult = (List)result.get();
			for (Object element:lResult) {
				if (element instanceof TypedValue) {
					TypedValue tv = (TypedValue)element;
					converted.add(SpdxModelFactory.createModelObject(modelStore, tv.getDocumentUri(), tv.getId(), tv.getType()));
				} else {
					converted.add(element);
				}
			}
			result = Optional.of(Collections.unmodifiableList(converted));
		} 
		return result;
	}

	/**
	 * Set a property value for a property name, creating the property if necessary
	 * @param stModelStore Model store for the properties
	 * @param stDocumentUri Unique document URI
	 * @param stId ID of the item to associate the property with
	 * @param propertyName Name of the property associated with this object
	 * @param value Value to associate with the property
	 * @throws InvalidSPDXAnalysisException
	 */
	public static void setPropertyValue(IModelStore stModelStore, String stDocumentUri, String stId, String propertyName, Object value) throws InvalidSPDXAnalysisException {
		Objects.requireNonNull(stModelStore);
		Objects.requireNonNull(stDocumentUri);
		Objects.requireNonNull(stId);
		Objects.requireNonNull(propertyName);
		if (value == null) {
			// we just remove the value
			removeProperty(stModelStore, stDocumentUri, stId, propertyName);
		} else if (value instanceof ModelObject) {
			ModelObject mValue = (ModelObject)value;
			if (!mValue.getModelStore().equals(stModelStore)) {
				if (stModelStore.exists(mValue.getDocumentUri(), mValue.getId())) {
					stModelStore.copyFrom(mValue.getDocumentUri(), mValue.getId(), mValue.getType(), mValue.getModelStore());
				}
			}
			stModelStore.setValue(stDocumentUri, stId, propertyName, mValue.toTypeValue());
		} else {
			stModelStore.setValue(stDocumentUri, stId, propertyName, value);
		}	
	}
	
	/**
	 * Set a property value for a property name, creating the property if necessary
	 * @param propertyName Name of the property associated with this object
	 * @param value Value to associate with the property
	 * @throws InvalidSPDXAnalysisException 
	 */
	public void setPropertyValue(String propertyName, Object value) throws InvalidSPDXAnalysisException {
		setPropertyValue(this.modelStore, this.documentUri, this.id, propertyName, value);
	}
	
	/**
	 * Create an update when, when applied by the ModelStore, sets a property value for a property name, creating the property if necessary
	 * @param propertyName Name of the property associated with this object
	 * @param value Value to associate with the property
	 * @return an update which can be applied by invoking the apply method
	 */
	public ModelUpdate updatePropertyValue(String propertyName, Object value) {
		return () ->{
			setPropertyValue(this.modelStore, this.documentUri, this.id, propertyName, value);
		};
	}
	
	/**
	 * @param propertyName Name of a property
	 * @return the Optional String value associated with a property, null if no value is present
	 * @throws SpdxInvalidTypeException
	 */
	public Optional<String> getStringPropertyValue(String propertyName) throws InvalidSPDXAnalysisException {
		Optional<Object> result = getObjectPropertyValue(propertyName);
		Optional<String> retval;
		if (result.isPresent()) {
			if (!(result.get() instanceof String)) {
				throw new SpdxInvalidTypeException("Property "+propertyName+" is not of type String");
			}
			retval = Optional.of((String)result.get());
		} else {
			retval = Optional.empty();
		}
		return retval;
	}
	
	/**
	 * @param propertyName Name of the property
	 * @return the Optional Boolean value for a property
	 * @throws SpdxInvalidTypeException
	 */
	public Optional<Boolean> getBooleanPropertyValue(String propertyName) throws InvalidSPDXAnalysisException {
		Optional<Object> result = getObjectPropertyValue(propertyName);
		Optional<Boolean> retval;
		if (result.isPresent()) {
			if (!(result.get() instanceof Boolean)) {
				throw new SpdxInvalidTypeException("Property "+propertyName+" is not of type Boolean");
			}
			retval = Optional.of((Boolean)result.get());
		} else {
			retval = Optional.empty();
		}
		return retval;
	}
	
	/**
	 * Removes a property and its value from the model store if it exists
	 * @param stModelStore Model store for the properties
	 * @param stDocumentUri Unique document URI
	 * @param stId ID of the item to associate the property with
	 * @param propertyName Name of the property associated with this object to be removed
	 * @throws InvalidSPDXAnalysisException
	 */
	public static void removeProperty(IModelStore stModelStore, String stDocumentUri, String stId, String propertyName) throws InvalidSPDXAnalysisException {
		stModelStore.removeProperty(stDocumentUri, stId, propertyName);
	}
	
	/**
	 * Removes a property and its value from the model store if it exists
	 * @param propertyName Name of the property associated with this object to be removed
	 * @throws InvalidSPDXAnalysisException
	 */
	public void removeProperty(String propertyName) throws InvalidSPDXAnalysisException {
		removeProperty(modelStore, documentUri, id, propertyName);
	}
	
	/**
	 * Create an update when, when applied by the ModelStore, removes a property and its value from the model store if it exists
	 * @param propertyName Name of the property associated with this object to be removed
	 * @return  an update which can be applied by invoking the apply method
	 */
	public ModelUpdate updateRemoveProperty(String propertyName) {
		return () -> {
			removeProperty(modelStore, documentUri, id, propertyName);
		};
	}
	
	// The following methods manage lists of values associated with a property
	/**
	 * Clears a list of values associated with a property
	 * @param stModelStore Model store for the properties
	 * @param stDocumentUri Unique document URI
	 * @param stId ID of the item to associate the property with
	 * @param propertyName Name of the property
	 * @throws InvalidSPDXAnalysisException
	 */
	public static void clearPropertyValueList(IModelStore stModelStore, String stDocumentUri, String stId, String propertyName) throws InvalidSPDXAnalysisException {
		stModelStore.clearPropertyValueList(stDocumentUri, stId, propertyName);
	}
	
	/**
	 * Clears a list of values associated with a property
	 * @param propertyName Name of the property
	 */
	public void clearPropertyValueList(String propertyName) throws InvalidSPDXAnalysisException {
		clearPropertyValueList(modelStore, documentUri, id, propertyName);
	}
	
	/**
	 * Create an update when, when applied by the ModelStore, clears a list of values associated with a property
	 * @param propertyName Name of the property
	 * @return an update which can be applied by invoking the apply method
	 */
	public ModelUpdate updateClearPropertyValueList(String propertyName) {
		return () ->{
			clearPropertyValueList(modelStore, documentUri, id, propertyName);
		};
	}
	
	/**
	 * Add a value to a list of values associated with a property.  If a value is a ModelObject and does not
	 * belong to the document, it will be copied into the object store
	 * @param stModelStore Model store for the properties
	 * @param stDocumentUri Unique document URI
	 * @param stId ID of the item to associate the property with
	 * @param propertyName  Name of the property
	 * @param value to add
	 * @throws InvalidSPDXAnalysisException 
	 */
	public static void addPropertyValueToList(IModelStore stModelStore, String stDocumentUri, String stId, 
			String propertyName, Object value) throws InvalidSPDXAnalysisException {
		if (value instanceof ModelObject) {
			ModelObject mValue = (ModelObject)value;
			if (!mValue.getModelStore().equals(stModelStore)) {
				if (!stModelStore.exists(mValue.getDocumentUri(), mValue.getId())) {
					stModelStore.copyFrom(mValue.getDocumentUri(), mValue.getId(), mValue.getType(), mValue.getModelStore());
				}
			}
			stModelStore.addValueToList(stDocumentUri, stId, propertyName, mValue.toTypeValue());
		} else {
			stModelStore.addValueToList(stDocumentUri, stId, propertyName, value);
		}
	}
	
	/**
	 * Add a value to a list of values associated with a property.  If a value is a ModelObject and does not
	 * belong to the document, it will be copied into the object store
	 * @param propertyName  Name of the property
	 * @param value to add
	 * @throws InvalidSPDXAnalysisException 
	 */
	public void addPropertyValueToList(String propertyName, Object value) throws InvalidSPDXAnalysisException {
		addPropertyValueToList(modelStore, documentUri, id, propertyName, value);
	}
	
	/**
	 * Create an update when, when applied, adds a value to a list of values associated with a property.  If a value is a ModelObject and does not
	 * belong to the document, it will be copied into the object store
	 * @param propertyName  Name of the property
	 * @param value to add
	 * @return an update which can be applied by invoking the apply method
	 */
	public ModelUpdate updateAddPropertyValueToList(String propertyName, Object value) {
		return () ->{
			addPropertyValueToList(modelStore, documentUri, id, propertyName, value);
		};
	}
	
	/**
	 * Replace the entire value list for a property.  If a value is a ModelObject and does not
	 * belong to the document, it will be copied into the object store
	 * @param stModelStore Model store for the properties
	 * @param stDocumentUri Unique document URI
	 * @param stId ID of the item to associate the property with
	 * @param propertyName name of the property
	 * @param values list of new properties
	 * @throws InvalidSPDXAnalysisException 
	 */
	public static void replacePropertyValueList(IModelStore stModelStore, String stDocumentUri, String stId, 
			String propertyName, List<?> values) throws InvalidSPDXAnalysisException {
		clearPropertyValueList(stModelStore, stDocumentUri, stId, propertyName);
		for (Object value:values) {
			addPropertyValueToList(stModelStore, stDocumentUri, stId, propertyName, value);
		}
	}
	
	/**
	 * Replace the entire value list for a property.  If a value is a ModelObject and does not
	 * belong to the document, it will be copied into the object store
	 * @param propertyName name of the property
	 * @param values list of new properties
	 * @throws InvalidSPDXAnalysisException 
	 */
	public void replacePropertyValueList(String propertyName, List<?> values) throws InvalidSPDXAnalysisException {
		replacePropertyValueList(modelStore, documentUri, id, propertyName, values);
	}
	
	/**
	 * When applied, replace the entire value list for a property.  If a value is a ModelObject and does not
	 * belong to the document, it will be copied into the object store
	 * @param propertyName name of the property
	 * @param values list of new properties
	 * @return an update which can be applied by invoking the apply method
	 * @throws InvalidSPDXAnalysisException 
	 */
	public ModelUpdate updateReplacePropertyValueList(String propertyName, List<?> values) {
		return () ->{
			replacePropertyValueList(modelStore, documentUri, id, propertyName, values);
		};
	}
	
	/**
	 * Remove a property value from a list
	 * @param stModelStore Model store for the properties
	 * @param stDocumentUri Unique document URI
	 * @param stId ID of the item to associate the property with
	 * @param propertyName name of the property
	 * @param value Value to be removed
	 * @throws InvalidSPDXAnalysisException
	 */
	public static void removePropertyValueFromList(IModelStore stModelStore, String stDocumentUri, String stId, 
			String propertyName, Object value) throws InvalidSPDXAnalysisException {
		if (value instanceof ModelObject) {
			stModelStore.removePropertyValueFromList(stDocumentUri, stId, propertyName, ((ModelObject)value).toTypeValue());
		} else {
			stModelStore.removePropertyValueFromList(stDocumentUri, stId, propertyName, value);
		}
	}
	
	/**
	 * Remove a property value from a list
	 * @param propertyName name of the property
	 * @param value Value to be removed
	 * @throws InvalidSPDXAnalysisException
	 */
	public void removePropertyValueFromList(String propertyName, Object value) throws InvalidSPDXAnalysisException {
		removePropertyValueFromList(modelStore, documentUri, id, propertyName, value);
	}
	
	/**
	 * Create an update when, when applied, removes a property value from a list
	 * @param propertyName name of the property
	 * @param value Value to be removed
	 * @return an update which can be applied by invoking the apply method
	 */
	public ModelUpdate updateRemovePropertyValueFromList(String propertyName, Object value) {
		return () -> {
			removePropertyValueFromList(modelStore, documentUri, id, propertyName, value);
		};
	}
	
	/**
	 * @param propertyName Name of the property
	 * @return List of values associated with a property
	 */
	public List<?> getObjectPropertyValueList(String propertyName) throws InvalidSPDXAnalysisException {
		Optional<Object> retval = getObjectPropertyValue(propertyName);
		if (!retval.isPresent()) {
			return new ArrayList<>();	// return an empty list
		}
		if (!(retval.get() instanceof List<?>)) {
			throw new SpdxInvalidTypeException("Expected a list for the value of property "+propertyName);
		}
		return (List<?>)retval.get();
	}
	
	/**
	 * @param propertyName Name of property
	 * @return List of Strings associated with the property
	 * @throws SpdxInvalidTypeException
	 */
	@SuppressWarnings("unchecked")
	public List<String> getStringPropertyValueList(String propertyName) throws InvalidSPDXAnalysisException {
		List<?> oList = getObjectPropertyValueList(propertyName);
		if (oList == null) {
			return null;
		}
		if (oList.size() > 0 && (!(oList.get(0) instanceof String))) {
			throw new SpdxInvalidTypeException("Property "+propertyName+" does not contain a list of Strings");
		}
		return (List<String>)oList;
	}
	
	/**
	 * @param compare
	 * @return true if all the properties have the same or equivalent values
	 */
	public boolean equivalent(ModelObject compare) throws InvalidSPDXAnalysisException {
		if (!this.getClass().equals(compare.getClass())) {
			return false;
		}
		List<String> propertyValueNames = getPropertyValueNames();
		List<String> comparePropertyValueNames = new ArrayList<String>(compare.getPropertyValueNames());	// create a copy since we're going to modify it
		for (String propertyName:propertyValueNames) {
			if (comparePropertyValueNames.contains(propertyName)) {
				if (!Objects.equals(this.getObjectPropertyValue(propertyName), compare.getObjectPropertyValue(propertyName))) {
					return false;
				}
				comparePropertyValueNames.remove(propertyName);
			} else {
				// No property value
				if (this.getObjectPropertyValue(propertyName) != null) {
					return false;
				}
			}
		}
		for (String propertyName:comparePropertyValueNames) {
			if (compare.getObjectPropertyValue(propertyName) != null) {
				return false;
			}
		}
		List<String> propertyValueListNames = getPropertyValueListNames();
		List<String> comparePropertyValueListNames = new ArrayList<String>(compare.getPropertyValueListNames());	// create a copy since we're going to modify it
		for (String propertyName:propertyValueListNames) {
			if (comparePropertyValueListNames.contains(propertyName)) {
				List<?> myList = getObjectPropertyValueList(propertyName);
				List<?> compList = compare.getObjectPropertyValueList(propertyName);
				int numRemainingComp = compList.size();
				for (Object item:myList) {
					if (compList.contains(item)) {
						numRemainingComp--;
					} else {
						return false;
					}
				}
				if (numRemainingComp > 0) {
					return false;
				}
				comparePropertyValueListNames.remove(propertyName);
			} else {
				// No property value
				if (!this.getObjectPropertyValueList(propertyName).isEmpty()) {
					return false;
				}
			}
		}
		if (!comparePropertyValueListNames.isEmpty()) {
			return false;
		}
		return true;
	}

	@Override
	public int hashCode() {
		if (this.id != null) {
			return this.id.toLowerCase().hashCode() ^ this.documentUri.hashCode();
		} else {
			return 0;
		}
	}
	/* (non-Javadoc)
	 * @see org.spdx.rdfparser.license.AnyLicenseInfo#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(Object o) {
		if (o == this) {
			return true;
		}
		if (!(o instanceof ModelObject)) {
			// covers o == null, as null is not an instance of anything
			return false;
		}
		ModelObject comp = (ModelObject)o;
		return Objects.equals(id, comp.getId()) && Objects.equals(documentUri, comp.getDocumentUri());
	}
	

	
	public Object clone() {
		try {
			return SpdxModelFactory.createModelObject(modelStore, documentUri, id, getType());
		} catch (InvalidSPDXAnalysisException e) {
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * Copy all the properties from the source object
	 * @param source
	 * @throws InvalidSPDXAnalysisException 
	 */
	public void copyFrom(ModelObject source) throws InvalidSPDXAnalysisException {
		List<String> propertyValueNames = source.getPropertyValueNames();
		for (String propertyName:propertyValueNames) {
			setPropertyValue(propertyName, source.getObjectPropertyValue(propertyName));
		}
		List<String> propertyValueListNames = source.getPropertyValueListNames();
		for (String propertyName:propertyValueListNames) {
			replacePropertyValueList(propertyName, source.getObjectPropertyValueList(propertyName));
		}
	}
	
	/**
	 * @param id String for the object
	 * @return type of the ID
	 */
	IdType idToIdType(String id) {
		if (id.startsWith(NON_STD_LICENSE_ID_PRENUM)) {
			return IdType.LicenseRef;
		} else if (id.startsWith(SPDX_ELEMENT_REF_PRENUM)) {
			return IdType.SpdxId;
		} else if (id.startsWith(EXTERNAL_DOC_REF_PRENUM)) {
			return IdType.DocumentRef;
		} else if (ListedLicenses.getListedLicenses().isSpdxListedLicenseId(id)) {
			return IdType.ListedLicense;
		} else if ("none".equals(id) || "noassertion".equals(id)) {
			return IdType.Literal;
		} else {
			return IdType.Anonomous;
		}
	}
	
	TypedValue toTypeValue() throws InvalidSPDXAnalysisException {
		return new TypedValue(this.documentUri, this.id, this.getType());
	}
}
