/*
 * XSDContentModelValidator.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 * For more information please visit https://www.nongnu.org/gonzalez/
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gonzalez.schema.xsd;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates element content against XSD complex type content models.
 *
 * <p>This validator uses a state-machine approach to track position within
 * content models (sequence, choice, all) and validate element order and
 * occurrence constraints.
 *
 * <p>Usage:
 * <pre>
 *   XSDContentModelValidator validator = new XSDContentModelValidator();
 *   validator.startValidation(complexType);
 *   for each child element:
 *       ValidationResult result = validator.validateElement(uri, localName);
 *       if (!result.isValid()) handle error
 *   ValidationResult result = validator.endValidation();
 *   if (!result.isValid()) handle error
 * </pre>
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class XSDContentModelValidator {
    
    /**
     * Result of a validation operation.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String errorCode;
        private final String errorMessage;
        private final XSDElement matchedElement;
        
        private ValidationResult(boolean valid, String errorCode, String errorMessage,
                                 XSDElement matchedElement) {
            this.valid = valid;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.matchedElement = matchedElement;
        }
        
        public static ValidationResult valid(XSDElement element) {
            return new ValidationResult(true, null, null, element);
        }
        
        public static ValidationResult validNoElement() {
            return new ValidationResult(true, null, null, null);
        }
        
        public static ValidationResult error(String errorCode, String message) {
            return new ValidationResult(false, errorCode, message, null);
        }
        
        public boolean isValid() {
            return valid;
        }
        
        public String getErrorCode() {
            return errorCode;
        }
        
        public String getErrorMessage() {
            return errorMessage;
        }
        
        public XSDElement getMatchedElement() {
            return matchedElement;
        }
    }
    
    /**
     * State for tracking position within a particle during validation.
     */
    private static class ParticleState {
        final XSDParticle particle;
        int childIndex;           // Current position in sequence/choice children
        int occurrenceCount;      // How many times this particle has been matched
        Set<String> seenElements; // For ALL groups: tracks which elements seen
        boolean choiceMade;       // For CHOICE: whether a branch was selected
        int choiceBranch;         // For CHOICE: which branch was selected
        
        ParticleState(XSDParticle particle) {
            this.particle = particle;
            this.childIndex = 0;
            this.occurrenceCount = 0;
            this.seenElements = null;
            this.choiceMade = false;
            this.choiceBranch = -1;
            
            if (particle.getKind() == XSDParticle.Kind.ALL) {
                this.seenElements = new HashSet<>();
            }
        }
    }
    
    private XSDComplexType currentType;
    private Deque<ParticleState> stateStack;
    private List<XSDParticle> topLevelParticles;
    private int topLevelIndex;
    private String lastError;
    private String lastErrorCode;
    
    /**
     * Creates a new content model validator.
     */
    public XSDContentModelValidator() {
        this.stateStack = new ArrayDeque<>();
    }
    
    /**
     * Starts validation for a complex type's content model.
     *
     * @param type the complex type to validate against
     */
    public void startValidation(XSDComplexType type) {
        this.currentType = type;
        this.stateStack.clear();
        this.topLevelParticles = type.getParticles();
        this.topLevelIndex = 0;
        this.lastError = null;
        this.lastErrorCode = null;
    }
    
    /**
     * Validates that an element is allowed at the current position in the content model.
     *
     * @param namespaceURI the element namespace URI (may be null)
     * @param localName the element local name
     * @return validation result indicating success or failure with error details
     */
    public ValidationResult validateElement(String namespaceURI, String localName) {
        if (currentType == null) {
            return ValidationResult.error("XTTE0520", "No content model to validate against");
        }
        
        XSDComplexType.ContentType contentType = currentType.getContentType();
        
        // Empty and simple content types don't allow child elements
        if (contentType == XSDComplexType.ContentType.EMPTY) {
            return ValidationResult.error("XTTE0520", 
                "Element not allowed: type has empty content model");
        }
        if (contentType == XSDComplexType.ContentType.SIMPLE) {
            return ValidationResult.error("XTTE0520",
                "Element not allowed: type has simple content (text only)");
        }
        
        // No particles means any element is invalid (unless mixed with no model)
        if (topLevelParticles.isEmpty()) {
            if (currentType.isMixed()) {
                // Mixed content with no particle model - no elements allowed
                return ValidationResult.error("XTTE0520",
                    "Element {" + namespaceURI + "}" + localName + " not allowed in content model");
            }
            return ValidationResult.error("XTTE0520",
                "Element {" + namespaceURI + "}" + localName + " not allowed: empty content model");
        }
        
        // Try to match the element against the content model
        return matchElement(namespaceURI, localName);
    }
    
    /**
     * Attempts to match an element against the content model particles.
     */
    private ValidationResult matchElement(String namespaceURI, String localName) {
        // If we have an active particle state, try to match within it
        if (!stateStack.isEmpty()) {
            ValidationResult result = matchInCurrentState(namespaceURI, localName);
            if (result != null) {
                return result;
            }
        }
        
        // Try to match at top level
        while (topLevelIndex < topLevelParticles.size()) {
            XSDParticle particle = topLevelParticles.get(topLevelIndex);
            ValidationResult result = tryMatchParticle(particle, namespaceURI, localName);
            
            if (result != null && result.isValid()) {
                return result;
            }
            
            // Check if current particle's minOccurs is satisfied before moving on
            ParticleState state = stateStack.isEmpty() ? null : stateStack.peek();
            if (state != null && state.particle == particle) {
                if (state.occurrenceCount < particle.getMinOccurs()) {
                    return ValidationResult.error("XTTE0520",
                        "Element {" + namespaceURI + "}" + localName + 
                        " not allowed: expected more occurrences of previous content");
                }
                stateStack.pop();
            }
            
            topLevelIndex++;
        }
        
        // No match found
        return ValidationResult.error("XTTE0520",
            "Element {" + namespaceURI + "}" + localName + " not allowed by content model");
    }
    
    /**
     * Tries to match an element within the current particle state.
     */
    private ValidationResult matchInCurrentState(String namespaceURI, String localName) {
        ParticleState state = stateStack.peek();
        XSDParticle particle = state.particle;
        
        switch (particle.getKind()) {
            case SEQUENCE:
                return matchInSequence(state, namespaceURI, localName);
            case CHOICE:
                return matchInChoice(state, namespaceURI, localName);
            case ALL:
                return matchInAll(state, namespaceURI, localName);
            case ELEMENT:
                return matchElementParticle(state, namespaceURI, localName);
            case ANY:
                return matchAnyParticle(state, namespaceURI, localName);
            default:
                return null;
        }
    }
    
    /**
     * Matches an element within a sequence particle.
     */
    private ValidationResult matchInSequence(ParticleState state, String namespaceURI, 
                                              String localName) {
        List<XSDParticle> children = state.particle.getChildren();
        
        while (state.childIndex < children.size()) {
            XSDParticle child = children.get(state.childIndex);
            ValidationResult result = tryMatchParticle(child, namespaceURI, localName);
            
            if (result != null && result.isValid()) {
                return result;
            }
            
            // Check if child's minOccurs is satisfied
            ParticleState childState = getChildState(child);
            int occurrences = childState != null ? childState.occurrenceCount : 0;
            
            if (occurrences < child.getMinOccurs()) {
                // Current child not satisfied, can't skip
                return null;
            }
            
            // Child satisfied, try next
            if (childState != null) {
                stateStack.pop();
            }
            state.childIndex++;
        }
        
        // All children exhausted, check if we can repeat the sequence
        int maxOccurs = state.particle.getMaxOccurs();
        if (maxOccurs == -1 || state.occurrenceCount < maxOccurs) {
            // Try repeating from the beginning
            state.childIndex = 0;
            state.occurrenceCount++;
            return matchInSequence(state, namespaceURI, localName);
        }
        
        return null;
    }
    
    /**
     * Matches an element within a choice particle.
     */
    private ValidationResult matchInChoice(ParticleState state, String namespaceURI,
                                            String localName) {
        List<XSDParticle> children = state.particle.getChildren();
        
        if (state.choiceMade) {
            // Already made a choice, check if we can repeat
            int maxOccurs = state.particle.getMaxOccurs();
            if (maxOccurs == -1 || state.occurrenceCount < maxOccurs) {
                // Can repeat, reset choice
                state.choiceMade = false;
                state.choiceBranch = -1;
            } else {
                return null;
            }
        }
        
        // Try each branch
        for (int i = 0; i < children.size(); i++) {
            XSDParticle child = children.get(i);
            ValidationResult result = tryMatchParticle(child, namespaceURI, localName);
            
            if (result != null && result.isValid()) {
                state.choiceMade = true;
                state.choiceBranch = i;
                state.occurrenceCount++;
                return result;
            }
        }
        
        return null;
    }
    
    /**
     * Matches an element within an all particle.
     */
    private ValidationResult matchInAll(ParticleState state, String namespaceURI,
                                         String localName) {
        List<XSDParticle> children = state.particle.getChildren();
        String elementKey = makeElementKey(namespaceURI, localName);
        
        // Check if already seen (unless repeatable)
        if (state.seenElements.contains(elementKey)) {
            // Check if element is repeatable
            for (XSDParticle child : children) {
                if (child.getKind() == XSDParticle.Kind.ELEMENT) {
                    XSDElement elem = child.getElement();
                    if (matchesElement(elem, namespaceURI, localName)) {
                        if (child.getMaxOccurs() == 1) {
                            return ValidationResult.error("XTTE0520",
                                "Element {" + namespaceURI + "}" + localName + 
                                " already appeared in all group");
                        }
                        // Repeatable, allow
                        break;
                    }
                }
            }
        }
        
        // Try to match
        for (XSDParticle child : children) {
            ValidationResult result = tryMatchParticle(child, namespaceURI, localName);
            if (result != null && result.isValid()) {
                state.seenElements.add(elementKey);
                return result;
            }
        }
        
        return null;
    }
    
    /**
     * Matches an element against an element particle.
     */
    private ValidationResult matchElementParticle(ParticleState state, String namespaceURI,
                                                   String localName) {
        XSDElement elem = state.particle.getElement();
        
        if (matchesElement(elem, namespaceURI, localName)) {
            int maxOccurs = state.particle.getMaxOccurs();
            if (maxOccurs != -1 && state.occurrenceCount >= maxOccurs) {
                return null; // Max occurrences reached
            }
            state.occurrenceCount++;
            return ValidationResult.valid(elem);
        }
        
        return null;
    }
    
    /**
     * Matches an element against an any (wildcard) particle.
     */
    private ValidationResult matchAnyParticle(ParticleState state, String namespaceURI,
                                               String localName) {
        if (matchesWildcard(state.particle, namespaceURI)) {
            int maxOccurs = state.particle.getMaxOccurs();
            if (maxOccurs != -1 && state.occurrenceCount >= maxOccurs) {
                return null;
            }
            state.occurrenceCount++;
            return ValidationResult.validNoElement();
        }
        return null;
    }
    
    /**
     * Tries to match a particle, creating state if needed.
     */
    private ValidationResult tryMatchParticle(XSDParticle particle, String namespaceURI,
                                               String localName) {
        switch (particle.getKind()) {
            case ELEMENT:
                XSDElement elem = particle.getElement();
                if (matchesElement(elem, namespaceURI, localName)) {
                    // Check occurrence constraints
                    ParticleState state = getOrCreateState(particle);
                    int maxOccurs = particle.getMaxOccurs();
                    if (maxOccurs != -1 && state.occurrenceCount >= maxOccurs) {
                        return null;
                    }
                    state.occurrenceCount++;
                    return ValidationResult.valid(elem);
                }
                return null;
                
            case SEQUENCE:
            case CHOICE:
            case ALL:
                ParticleState state = getOrCreateState(particle);
                stateStack.push(state);
                ValidationResult result = matchInCurrentState(namespaceURI, localName);
                if (result == null || !result.isValid()) {
                    stateStack.pop();
                }
                return result;
                
            case ANY:
                if (matchesWildcard(particle, namespaceURI)) {
                    ParticleState anyState = getOrCreateState(particle);
                    int maxOccurs = particle.getMaxOccurs();
                    if (maxOccurs != -1 && anyState.occurrenceCount >= maxOccurs) {
                        return null;
                    }
                    anyState.occurrenceCount++;
                    return ValidationResult.validNoElement();
                }
                return null;
                
            default:
                return null;
        }
    }
    
    /**
     * Completes validation, checking that all required elements are present.
     *
     * @return validation result
     */
    public ValidationResult endValidation() {
        if (currentType == null) {
            return ValidationResult.validNoElement();
        }
        
        // Check all remaining required particles
        while (topLevelIndex < topLevelParticles.size()) {
            XSDParticle particle = topLevelParticles.get(topLevelIndex);
            ValidationResult result = checkMinOccurs(particle);
            if (!result.isValid()) {
                return result;
            }
            topLevelIndex++;
        }
        
        // Check any remaining state on stack
        while (!stateStack.isEmpty()) {
            ParticleState state = stateStack.pop();
            ValidationResult result = checkStateComplete(state);
            if (!result.isValid()) {
                return result;
            }
        }
        
        return ValidationResult.validNoElement();
    }
    
    /**
     * Checks that a particle's minOccurs constraint is satisfied.
     */
    private ValidationResult checkMinOccurs(XSDParticle particle) {
        ParticleState state = getChildState(particle);
        int occurrences = state != null ? state.occurrenceCount : 0;
        
        if (occurrences < particle.getMinOccurs()) {
            String desc = describeParticle(particle);
            return ValidationResult.error("XTTE0510",
                "Required content missing: " + desc + " (minOccurs=" + particle.getMinOccurs() + 
                ", found=" + occurrences + ")");
        }
        
        // For model groups, check children
        if (state != null) {
            return checkStateComplete(state);
        }
        
        return ValidationResult.validNoElement();
    }
    
    /**
     * Checks that a particle state is complete (all required children present).
     */
    private ValidationResult checkStateComplete(ParticleState state) {
        XSDParticle particle = state.particle;
        
        switch (particle.getKind()) {
            case SEQUENCE:
                // Check all remaining children have minOccurs=0
                List<XSDParticle> seqChildren = particle.getChildren();
                for (int i = state.childIndex; i < seqChildren.size(); i++) {
                    XSDParticle child = seqChildren.get(i);
                    if (child.getMinOccurs() > 0) {
                        String desc = describeParticle(child);
                        return ValidationResult.error("XTTE0510",
                            "Required element missing in sequence: " + desc);
                    }
                }
                break;
                
            case CHOICE:
                if (particle.getMinOccurs() > 0 && !state.choiceMade) {
                    return ValidationResult.error("XTTE0510",
                        "Required choice not made: one of the alternatives must be present");
                }
                break;
                
            case ALL:
                // Check all required elements were seen
                List<XSDParticle> allChildren = particle.getChildren();
                for (XSDParticle child : allChildren) {
                    if (child.getMinOccurs() > 0 && child.getKind() == XSDParticle.Kind.ELEMENT) {
                        XSDElement elem = child.getElement();
                        String key = makeElementKey(elem.getNamespaceURI(), elem.getName());
                        if (!state.seenElements.contains(key)) {
                            return ValidationResult.error("XTTE0510",
                                "Required element missing in all group: " + elem.getName());
                        }
                    }
                }
                break;
                
            default:
                break;
        }
        
        return ValidationResult.validNoElement();
    }
    
    /**
     * Gets or creates a state for the given particle.
     */
    private ParticleState getOrCreateState(XSDParticle particle) {
        // Check if we already have a state for this particle
        for (ParticleState state : stateStack) {
            if (state.particle == particle) {
                return state;
            }
        }
        return new ParticleState(particle);
    }
    
    /**
     * Gets existing state for a child particle, if any.
     */
    private ParticleState getChildState(XSDParticle particle) {
        for (ParticleState state : stateStack) {
            if (state.particle == particle) {
                return state;
            }
        }
        return null;
    }
    
    /**
     * Checks if an element matches an XSD element declaration.
     */
    private boolean matchesElement(XSDElement elem, String namespaceURI, String localName) {
        if (!elem.getName().equals(localName)) {
            return false;
        }
        String elemNs = elem.getNamespaceURI();
        if (elemNs == null || elemNs.isEmpty()) {
            return namespaceURI == null || namespaceURI.isEmpty();
        }
        return elemNs.equals(namespaceURI);
    }
    
    /**
     * Checks if a namespace matches a wildcard constraint.
     */
    private boolean matchesWildcard(XSDParticle particle, String namespaceURI) {
        String constraint = particle.getNamespaceConstraint();
        
        if ("##any".equals(constraint)) {
            return true;
        }
        if ("##other".equals(constraint)) {
            return namespaceURI != null && !namespaceURI.isEmpty();
        }
        if ("##local".equals(constraint)) {
            return namespaceURI == null || namespaceURI.isEmpty();
        }
        if ("##targetNamespace".equals(constraint)) {
            return true; // Simplified
        }
        
        // Space-separated list of URIs
        if (namespaceURI == null) {
            namespaceURI = "";
        }
        int start = 0;
        int len = constraint.length();
        while (start < len) {
            int end = constraint.indexOf(' ', start);
            if (end == -1) {
                end = len;
            }
            String ns = constraint.substring(start, end);
            if (ns.equals(namespaceURI)) {
                return true;
            }
            start = end + 1;
        }
        return false;
    }
    
    /**
     * Creates a unique key for an element.
     */
    private String makeElementKey(String namespaceURI, String localName) {
        if (namespaceURI == null || namespaceURI.isEmpty()) {
            return localName;
        }
        return "{" + namespaceURI + "}" + localName;
    }
    
    /**
     * Describes a particle for error messages.
     */
    private String describeParticle(XSDParticle particle) {
        switch (particle.getKind()) {
            case ELEMENT:
                XSDElement elem = particle.getElement();
                String ns = elem.getNamespaceURI();
                if (ns != null && !ns.isEmpty()) {
                    return "element {" + ns + "}" + elem.getName();
                }
                return "element " + elem.getName();
            case SEQUENCE:
                return "sequence";
            case CHOICE:
                return "choice";
            case ALL:
                return "all";
            case ANY:
                return "any";
            default:
                return particle.getKind().toString();
        }
    }
    
    /**
     * Resets the validator for reuse.
     */
    public void reset() {
        this.currentType = null;
        this.stateStack.clear();
        this.topLevelParticles = null;
        this.topLevelIndex = 0;
        this.lastError = null;
        this.lastErrorCode = null;
    }
}
