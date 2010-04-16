package org.springframework.flex.roo.addon.as.classpath.as3parser;

import java.io.File;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.osgi.service.component.ComponentContext;
import org.springframework.flex.roo.addon.as.classpath.ASMutablePhysicalTypeMetadataProvider;
import org.springframework.flex.roo.addon.as.classpath.ASPhysicalTypeCategory;
import org.springframework.flex.roo.addon.as.classpath.ASPhysicalTypeDetails;
import org.springframework.flex.roo.addon.as.classpath.ASPhysicalTypeIdentifier;
import org.springframework.flex.roo.addon.as.classpath.ASPhysicalTypeMetadata;
import org.springframework.flex.roo.addon.as.classpath.details.ASMemberHoldingTypeDetails;
import org.springframework.flex.roo.addon.as.classpath.details.ASMutableClassOrInterfaceTypeDetails;
import org.springframework.flex.roo.addon.as.model.ActionScriptType;
import org.springframework.flex.roo.addon.mojos.FlexPathResolver;
import org.springframework.roo.file.monitor.event.FileDetails;
import org.springframework.roo.file.monitor.event.FileEvent;
import org.springframework.roo.file.monitor.event.FileEventListener;
import org.springframework.roo.file.monitor.event.FileOperation;
import org.springframework.roo.metadata.MetadataDependencyRegistry;
import org.springframework.roo.metadata.MetadataItem;
import org.springframework.roo.metadata.MetadataService;
import org.springframework.roo.process.manager.FileManager;
import org.springframework.roo.project.Path;
import org.springframework.roo.project.PathResolver;
import org.springframework.roo.project.ProjectMetadata;
import org.springframework.roo.support.util.Assert;

@Component(immediate=true)
@Service
public class As3ParserMetadataProvider implements
		ASMutablePhysicalTypeMetadataProvider, FileEventListener {

	@Reference private FileManager fileManager;
	@Reference private MetadataService metadataService;
	@Reference private MetadataDependencyRegistry metadataDependencyRegistry;
	
	protected void activate(ComponentContext context) {
	}
	
	public void createPhysicalType(ASPhysicalTypeMetadata toCreate) {
		Assert.notNull(toCreate, "Metadata to create is required");
		ASPhysicalTypeDetails physicalTypeDetails = toCreate.getPhysicalTypeDetails();
		Assert.notNull(physicalTypeDetails, "Unable to parse '" + toCreate + "'");
		Assert.isInstanceOf(ASMutableClassOrInterfaceTypeDetails.class, physicalTypeDetails, "This implementation can only create class or interface types");
		ASMemberHoldingTypeDetails cit = (ASMemberHoldingTypeDetails) physicalTypeDetails;
		String fileIdentifier = toCreate.getPhysicalLocationCanonicalPath();
		As3ParserMutableClassOrInterfaceTypeDetails.createType(fileManager, cit, fileIdentifier);
	}

	public String findIdentifier(ActionScriptType actionScriptType) {
		Assert.notNull(actionScriptType, "ActionScript type to locate is required");
		FlexPathResolver pathResolver = getPathResolver();
		for (Path sourcePath : pathResolver.getFlexSourcePaths()) {
			String relativePath = actionScriptType.getFullyQualifiedTypeName().replace('.', File.separatorChar) + ".as";
			String fileIdentifier = pathResolver.getIdentifier(sourcePath, relativePath);
			if (fileManager.exists(fileIdentifier)) {
				// found the file, so use this one
				return ASPhysicalTypeIdentifier.createIdentifier(actionScriptType, sourcePath);
			}
		}
		return null;
	}

	public MetadataItem get(String metadataIdentificationString) {
		Assert.isTrue(ASPhysicalTypeIdentifier.isValid(metadataIdentificationString), "Metadata identification string '" + metadataIdentificationString + "' is not valid for this metadata provider");
		String fileIdentifier = obtainPathToIdentifier(metadataIdentificationString);
		metadataDependencyRegistry.deregisterDependencies(metadataIdentificationString);
		if (!fileManager.exists(fileIdentifier)) {
			// Couldn't find the file, so return null to distinguish from a file that was found but could not be parsed
			return null;
		}
		As3ParserClassMetadata result = new As3ParserClassMetadata(fileManager, fileIdentifier, metadataIdentificationString, metadataService, this);
		if (result.getPhysicalTypeDetails() != null && result.getPhysicalTypeDetails() instanceof ASMemberHoldingTypeDetails) {
			ASMutableClassOrInterfaceTypeDetails details = (ASMutableClassOrInterfaceTypeDetails) result.getPhysicalTypeDetails();
			if (details.getPhysicalTypeCategory() == ASPhysicalTypeCategory.CLASS && details.getExtendsTypes().size() == 1) {
				// This is a class, and it extends another class
				
				if (details.getSuperClass() != null) {
					// We have a dependency on the superclass, and there is metadata available for the superclass
					// We won't implement the full MetadataNotificationListener here, but rely on MetadataService's fallback
					// (which is to evict from cache and call get again given As3ParserMetadataProvider doesn't implement MetadataNotificationListener, then notify everyone we've changed)
					String superclassId = details.getSuperClass().getDeclaredByMetadataId();
					metadataDependencyRegistry.registerDependency(superclassId, result.getId());
				} else {
					// We have a dependency on the superclass, but no metadata is available
					// We're left with no choice but to register for every physical type change, in the hope we discover our parent someday (sad, isn't it? :-) )
					for (Path sourcePath : getPathResolver().getSourcePaths()) {
						String possibleSuperclass = ASPhysicalTypeIdentifier.createIdentifier(details.getExtendsTypes().get(0), sourcePath);
						metadataDependencyRegistry.registerDependency(possibleSuperclass, result.getId());
					}
				}
			}
		}
		return result;
	}

	public String getProvidesType() {
		return ASPhysicalTypeIdentifier.getMetadataIdentiferType();
	}
	
	public void onFileEvent(FileEvent fileEvent) {
		String fileIdentifier = fileEvent.getFileDetails().getCanonicalPath();
		
		if (fileIdentifier.endsWith(".as") && fileEvent.getOperation() != FileOperation.MONITORING_FINISH) {
			// file is of interest
			
			// figure out the ActionScriptType this should be
			FlexPathResolver pathResolver = getPathResolver();
			Path sourcePath = null;
			for (Path path : pathResolver.getFlexSourcePaths()) {
				if (new FileDetails(new File(pathResolver.getRoot(path)), null).isParentOf(fileIdentifier)) {
					sourcePath = path;
					break;
				}
			}
			if (sourcePath == null) {
				// the .as file is not under a source path, so ignore it
				return;
			}
			// determine the ActionScriptType for this file
			String relativePath = pathResolver.getRelativeSegment(fileIdentifier);
			Assert.hasText(relativePath, "Could not determine compilation unit name for file '" + fileIdentifier + "'");
			Assert.isTrue(relativePath.startsWith(File.separator), "Relative path unexpectedly dropped the '" + File.separator + "' prefix (received '" + relativePath + "' from '" + fileIdentifier + "'");
			relativePath = relativePath.substring(1);
			Assert.isTrue(relativePath.endsWith(".as"), "The relative path unexpectedly dropped the .as extension for file '" + fileIdentifier + "'");
			relativePath = relativePath.substring(0, relativePath.lastIndexOf(".as"));
			
			ActionScriptType actionScriptType = new ActionScriptType(relativePath.replace(File.separatorChar, '.'));
			
			// figure out the PhysicalTypeIdentifier
			String id = ASPhysicalTypeIdentifier.createIdentifier(actionScriptType, sourcePath);
			
			// Now we've worked out the id, we can publish the event in case others were interested
			metadataService.evict(id);
			metadataDependencyRegistry.notifyDownstream(id);
		}

	}
	
	private FlexPathResolver getPathResolver() {
		ProjectMetadata projectMetadata = (ProjectMetadata) metadataService.get(ProjectMetadata.getProjectIdentifier());
		Assert.notNull(projectMetadata, "Project metadata unavailable");
		PathResolver pathResolver = projectMetadata.getPathResolver();
		Assert.notNull(pathResolver, "Path resolver unavailable because valid project metadata not currently available");
		Assert.isInstanceOf(FlexPathResolver.class, "Path resolver is of an unexpected type, not appropriate for a Flex project.");
		return (FlexPathResolver) pathResolver;
	}
	
	private String obtainPathToIdentifier(String physicalTypeIdentifier) {
		Assert.isTrue(ASPhysicalTypeIdentifier.isValid(physicalTypeIdentifier), "Metadata identification string '" + physicalTypeIdentifier + "' is not valid for this metadata provider");
		Path path = ASPhysicalTypeIdentifier.getPath(physicalTypeIdentifier);
		ActionScriptType type = ASPhysicalTypeIdentifier.getActionScriptType(physicalTypeIdentifier);
		PathResolver pathResolver = getPathResolver();
		String relativePath = type.getFullyQualifiedTypeName().replace('.', File.separatorChar) + ".as";
		String fileIdentifier = pathResolver.getIdentifier(path, relativePath);
		return fileIdentifier;
	}

}
