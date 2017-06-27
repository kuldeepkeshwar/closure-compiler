/*
 * Copyright 2014 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.javascript.jscomp;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.javascript.jscomp.parsing.parser.FeatureSet.Feature.MODULES;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.deps.ModuleLoader;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.JSTypeExpression;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites a ES6 module into a form that can be safely concatenated. Note that we treat a file as
 * an ES6 module if it has at least one import or export statement.
 *
 * @author moz@google.com (Michael Zhou)
 */
public final class Es6RewriteModules extends AbstractPostOrderCallback
    implements HotSwapCompilerPass {
  private static final String DEFAULT_EXPORT_NAME = "$jscompDefaultExport";

  static final DiagnosticType LHS_OF_GOOG_REQUIRE_MUST_BE_CONST =
      DiagnosticType.error(
          "JSC_LHS_OF_GOOG_REQUIRE_MUST_BE_CONST",
          "The left side of a goog.require() must use ''const'' (not ''let'' or ''var'')");

  static final DiagnosticType NAMESPACE_IMPORT_CANNOT_USE_STAR =
      DiagnosticType.error(
          "JSC_NAMESPACE_IMPORT_CANNOT_USE_STAR",
          "Namespace imports ('goog:some.Namespace') cannot use import * as. "
              + "Did you mean to import {0} from ''{1}'';?");

  private final AbstractCompiler compiler;
  private int scriptNodeCount;

  /**
   * Maps exported names to their names in current module.
   */
  private Map<String, NameNodePair> exportMap;

  /**
   * Maps symbol names to a pair of (moduleName, originalName). The original
   * name is the name of the symbol exported by the module. This is required
   * because we want to be able to update the original property on the module
   * object. Eg: "import {foo as f} from 'm'" maps 'f' to the pair ('m', 'foo').
   * In the entry for "import * as ns", the originalName will be the empty string.
   */
  private Map<String, ModuleOriginalNamePair> importMap;

  private Set<String> classes;
  private Set<String> typedefs;

  private Set<String> alreadyRequired;

  private Node googRequireInsertSpot;

  /**
   * Creates a new Es6RewriteModules instance which can be used to rewrite
   * ES6 modules to a concatenable form.
   */
  public Es6RewriteModules(AbstractCompiler compiler) {
    this.compiler = compiler;
  }

  /**
   * Return whether or not the given script node represents an ES6 module file.
   */
  public static boolean isEs6ModuleRoot(Node scriptNode) {
    checkArgument(scriptNode.isScript());
    if (scriptNode.getBooleanProp(Node.GOOG_MODULE)) {
      return false;
    }
    return scriptNode.hasChildren() && scriptNode.getFirstChild().isModuleBody();
  }

  /**
   * Force rewriting of a file into an ES6 module, such as for imported files that contain no
   * "import" or "export" statements. Fails if the file contains a goog.provide or goog.module.
   *
   * @return True, if the file is now an ES6 module. False, if the file must remain a script.
   * TODO(blickly): Move this logic out of this pass, since it is independent of whether or
   * not we are actually transpiling modules
   */
  public boolean forceToEs6Module(Node root) {
    if (isEs6ModuleRoot(root)) {
      return true;
    }
    FindGoogProvideOrGoogModule finder = new FindGoogProvideOrGoogModule();
    NodeTraversal.traverseEs6(compiler, root, finder);
    if (finder.isFound()) {
      return false;
    }
    Node moduleNode = new Node(Token.MODULE_BODY).srcref(root);
    moduleNode.addChildrenToBack(root.removeChildren());
    root.addChildToBack(moduleNode);
    return true;
  }

  @Override
  public void process(Node externs, Node root) {
    checkState(compiler.getOptions().getLanguageIn().toFeatureSet().has(MODULES));
    for (Node file = root.getFirstChild(); file != null; file = file.getNext()) {
      hotSwapScript(file, null);
    }
    compiler.setFeatureSet(compiler.getFeatureSet().without(MODULES));
  }

  @Override
  public void hotSwapScript(Node scriptNode, Node originalRoot) {
    if (isEs6ModuleRoot(scriptNode)) {
      processFile(scriptNode);
    }
  }

  /**
   * Rewrite a single ES6 module file to a global script version.
   */
  private void processFile(Node root) {
    checkArgument(isEs6ModuleRoot(root), root);
    clearState();
    NodeTraversal.traverseEs6(compiler, root, this);
  }

  public void clearState() {
    this.scriptNodeCount = 0;
    this.exportMap = new LinkedHashMap<>();
    this.importMap = new HashMap<>();
    this.classes = new HashSet<>();
    this.typedefs = new HashSet<>();
    this.alreadyRequired = new HashSet<>();
    this.googRequireInsertSpot = null;
  }

  /**
   * Avoid processing if we find the appearance of goog.provide or goog.module.
   *
   * <p>TODO(moz): Let ES6, CommonJS and goog.provide live happily together.
   */
  static class FindGoogProvideOrGoogModule extends NodeTraversal.AbstractPreOrderCallback {

    private boolean found;

    boolean isFound() {
      return found;
    }

    @Override
    public boolean shouldTraverse(NodeTraversal nodeTraversal, Node n, Node parent) {
      if (found) {
        return false;
      }
      // Shallow traversal, since we don't need to inspect within functions or expressions.
      if (parent == null
          || NodeUtil.isControlStructure(parent)
          || NodeUtil.isStatementBlock(parent)) {
        if (n.isExprResult()) {
          Node maybeGetProp = n.getFirstFirstChild();
          if (maybeGetProp != null
              && (maybeGetProp.matchesQualifiedName("goog.provide")
                  || maybeGetProp.matchesQualifiedName("goog.module"))) {
            found = true;
            return false;
          }
        }
        return true;
      }
      return false;
    }
  }

  @Override
  public void visit(NodeTraversal t, Node n, Node parent) {
    if (n.isImport()) {
      visitImport(t, n, parent);
    } else if (n.isExport()) {
      visitExport(t, n, parent);
    } else if (n.isScript()) {
      scriptNodeCount++;
      visitScript(t, n);
    }
  }

  private void visitImport(NodeTraversal t, Node importDecl, Node parent) {
    checkArgument(parent.isModuleBody(), parent);
    String moduleName;
    String importName = importDecl.getLastChild().getString();
    boolean isNamespaceImport = importName.startsWith("goog:");
    if (isNamespaceImport) {
      // Allow importing Closure namespace objects (e.g. from goog.provide or goog.module) as
      //   import ... from 'goog:my.ns.Object'.
      // These are rewritten to plain namespace object accesses.
      moduleName = importName.substring("goog:".length());
    } else {
      ModuleLoader.ModulePath modulePath =
          t.getInput()
              .getPath()
              .resolveJsModule(
                  importName,
                  importDecl.getSourceFileName(),
                  importDecl.getLineno(),
                  importDecl.getCharno());
      if (modulePath == null) {
        // The module loader issues an error
        // Fall back to assuming the module is a file path
        modulePath = t.getInput().getPath().resolveModuleAsPath(importName);
      }

      moduleName = modulePath.toModuleName();
    }

    for (Node child : importDecl.children()) {
      if (child.isEmpty() || child.isString()) {
        continue;
      } else if (child.isName()) { // import a from "mod"
        // Namespace imports' default export is the namespace itself.
        String name = isNamespaceImport ? "" : "default";
        importMap.put(child.getString(), new ModuleOriginalNamePair(moduleName, name));
      } else if (child.isImportSpecs()) {
        for (Node grandChild : child.children()) {
          String origName = grandChild.getFirstChild().getString();
          if (grandChild.hasTwoChildren()) { // import {a as foo} from "mod"
            importMap.put(
                grandChild.getLastChild().getString(),
                new ModuleOriginalNamePair(moduleName, origName));
          } else { // import {a} from "mod"
            importMap.put(
                origName,
                new ModuleOriginalNamePair(moduleName, origName));
          }
        }
      } else {
        // import * as ns from "mod"
        Preconditions.checkState(
            child.isImportStar(), "Expected an IMPORT_STAR node, but was: %s", child);
        // Namespace imports cannot be imported "as *".
        if (isNamespaceImport) {
          compiler.report(t.makeError(importDecl, NAMESPACE_IMPORT_CANNOT_USE_STAR,
              child.getString(), moduleName));
        }
        importMap.put(
            child.getString(),
            new ModuleOriginalNamePair(moduleName, ""));
      }
    }

    // Emit goog.require call for the module.
    if (alreadyRequired.add(moduleName)) {
      Node require = IR.exprResult(
          IR.call(NodeUtil.newQName(compiler, "goog.require"), IR.string(moduleName)));
      require.useSourceInfoIfMissingFromForTree(importDecl);
      parent.addChildAfter(require, googRequireInsertSpot);
      googRequireInsertSpot = require;
      t.getInput().addRequire(moduleName);
    }

    parent.removeChild(importDecl);
    t.reportCodeChange();
  }

  private void visitExport(NodeTraversal t, Node export, Node parent) {
    checkArgument(parent.isModuleBody(), parent);
    if (export.getBooleanProp(Node.EXPORT_DEFAULT)) {
      // export default

      // If the thing being exported is a class or function that has a name,
      // extract it from the export statement, so that it can be referenced
      // from within the module.
      //
      //   export default class X {} -> class X {}; ... moduleName.default = X;
      //   export default function X() {} -> function X() {}; ... moduleName.default = X;
      //
      // Otherwise, create a local variable for it and export that.
      //
      //   export default 'someExpression'
      //     ->
      //   var $jscompDefaultExport = 'someExpression';
      //   ...
      //   moduleName.default = $jscompDefaultExport;
      Node child = export.getFirstChild();
      String name = null;

      if (child.isFunction() || child.isClass()) {
        name = NodeUtil.getName(child);
      }

      if (name != null) {
        Node decl = child.detach();
        parent.replaceChild(export, decl);
        exportMap.put("default", new NameNodePair(name, child));
      } else {
        Node var = IR.var(IR.name(DEFAULT_EXPORT_NAME), export.removeFirstChild());
        var.setJSDocInfo(child.getJSDocInfo());
        child.setJSDocInfo(null);
        var.useSourceInfoIfMissingFromForTree(export);
        parent.replaceChild(export, var);
        exportMap.put("default", new NameNodePair(DEFAULT_EXPORT_NAME, child));
      }
    } else if (export.getBooleanProp(Node.EXPORT_ALL_FROM)) {
      //   export * from 'moduleIdentifier';
      compiler.report(JSError.make(export, Es6ToEs3Converter.CANNOT_CONVERT_YET,
          "Wildcard export"));
    } else if (export.hasTwoChildren()) {
      //   export {x, y as z} from 'moduleIdentifier';
      Node moduleIdentifier = export.getLastChild();
      Node importNode = IR.importNode(IR.empty(), IR.empty(), moduleIdentifier.cloneNode());
      importNode.useSourceInfoFrom(export);
      parent.addChildBefore(importNode, export);
      visit(t, importNode, parent);

      ModuleLoader.ModulePath path =
          t.getInput()
              .getPath()
              .resolveJsModule(
                  moduleIdentifier.getString(),
                  export.getSourceFileName(),
                  export.getLineno(),
                  export.getCharno());
      if (path == null) {
        path = t.getInput().getPath().resolveModuleAsPath(moduleIdentifier.getString());
      }
      String moduleName = path.toModuleName();

      for (Node exportSpec : export.getFirstChild().children()) {
        String nameFromOtherModule = exportSpec.getFirstChild().getString();
        String exportedName = exportSpec.getLastChild().getString();
        exportMap.put(exportedName,
            new NameNodePair(moduleName + "." + nameFromOtherModule, exportSpec));
      }
      parent.removeChild(export);
    } else {
      if (export.getFirstChild().getToken() == Token.EXPORT_SPECS) {
        //     export {Foo};
        for (Node exportSpec : export.getFirstChild().children()) {
          Node origName = exportSpec.getFirstChild();
          exportMap.put(
              exportSpec.hasTwoChildren()
                  ? exportSpec.getLastChild().getString()
                  : origName.getString(),
              new NameNodePair(origName.getString(), exportSpec));
        }
        parent.removeChild(export);
      } else {
        //    export var Foo;
        //    export function Foo() {}
        // etc.
        Node declaration = export.getFirstChild();
        Node first = declaration.getFirstChild();
        for (Node maybeName = first; maybeName != null; maybeName = maybeName.getNext()) {
          if (!maybeName.isName()) {
            break;
          }
          // Break out on "B" in "class A extends B"
          if (declaration.isClass() && maybeName != first) {
            break;
          }
          String name = maybeName.getString();
          Var v = t.getScope().getVar(name);
          if (v == null || v.isGlobal()) {
            exportMap.put(name, new NameNodePair(name, maybeName));
          }

          // If the declaration declares a new type, create annotations for
          // the type checker.
          // TODO(moz): Currently we only record ES6 classes and typedefs,
          // need to handle other kinds of type declarations too.
          if (declaration.isClass()) {
            classes.add(name);
          }
          if (declaration.getJSDocInfo() != null && declaration.getJSDocInfo().hasTypedefType()) {
            typedefs.add(name);
          }
        }
        parent.replaceChild(export, declaration.detach());
      }
      t.reportCodeChange();
    }
  }

  private void inlineModuleToGlobalScope(Node moduleNode) {
    checkState(moduleNode.isModuleBody());
    Node scriptNode = moduleNode.getParent();
    moduleNode.detach();
    scriptNode.addChildrenToFront(moduleNode.removeChildren());
  }

  private void visitScript(NodeTraversal t, Node script) {

    inlineModuleToGlobalScope(script.getFirstChild());

    ClosureRewriteModule.checkAndSetStrictModeDirective(t, script);

    checkArgument(
        scriptNodeCount == 1,
        "Es6RewriteModules supports only one invocation per " + "CompilerInput / script node");

    // rewriteRequires is here (rather than being part of the main visit()
    // method, because we only want to rewrite the requires if this is an
    // ES6 module.
    rewriteRequires(script);

    String moduleName = t.getInput().getPath().toModuleName();

    for (Map.Entry<String, NameNodePair> entry : exportMap.entrySet()) {
      String exportedName = entry.getKey();
      String withSuffix = entry.getValue().name;
      Node nodeForSourceInfo = entry.getValue().nodeForSourceInfo;
      Node getProp = IR.getprop(IR.name(moduleName), IR.string(exportedName));

      if (typedefs.contains(exportedName)) {
        // /** @typedef {foo} */
        // moduleName.foo;
        JSDocInfoBuilder builder = new JSDocInfoBuilder(true);
        JSTypeExpression typeExpr = new JSTypeExpression(
            IR.string(exportedName), script.getSourceFileName());
        builder.recordTypedef(typeExpr);
        JSDocInfo info = builder.build();
        getProp.setJSDocInfo(info);
        Node exprResult = IR.exprResult(getProp)
            .useSourceInfoIfMissingFromForTree(nodeForSourceInfo);
        script.addChildToBack(exprResult);
      } else {
        //   moduleName.foo = foo;
        // with a @const annotation if needed.
        Node assign = IR.assign(
            getProp,
            NodeUtil.newQName(compiler, withSuffix));
        Node exprResult = IR.exprResult(assign)
            .useSourceInfoIfMissingFromForTree(nodeForSourceInfo);
        if (classes.contains(exportedName)) {
          JSDocInfoBuilder builder = new JSDocInfoBuilder(true);
          builder.recordConstancy();
          JSDocInfo info = builder.build();
          assign.setJSDocInfo(info);
        }
        script.addChildToBack(exprResult);
      }
    }

    // Rename vars to not conflict in global scope.
    NodeTraversal.traverseEs6(compiler, script, new RenameGlobalVars(moduleName));

    // Add goog.provide call.
    Node googProvide = IR.exprResult(
        IR.call(NodeUtil.newQName(compiler, "goog.provide"),
            IR.string(moduleName)));
    script.addChildToFront(googProvide.useSourceInfoIfMissingFromForTree(script));
    t.getInput().addProvide(moduleName);

    JSDocInfoBuilder jsDocInfo = script.getJSDocInfo() == null
        ? new JSDocInfoBuilder(false)
        : JSDocInfoBuilder.copyFrom(script.getJSDocInfo());
    if (!jsDocInfo.isPopulatedWithFileOverview()) {
      jsDocInfo.recordFileOverview("");
    }
    // Don't check provides and requires, since most of them are auto-generated.
    jsDocInfo.recordSuppressions(ImmutableSet.of("missingProvide", "missingRequire"));
    script.setJSDocInfo(jsDocInfo.build());

    exportMap.clear();
    t.reportCodeChange();
  }

  private void rewriteRequires(Node script) {
    NodeTraversal.traverseEs6(
        compiler,
        script,
        new NodeTraversal.AbstractShallowCallback() {
          @Override
          public void visit(NodeTraversal t, Node n, Node parent) {
            if (n.isCall()
                && n.getFirstChild().matchesQualifiedName("goog.require")
                && NodeUtil.isNameDeclaration(parent.getParent())) {
              visitRequire(n, parent);
            }
          }

          /**
           * Rewrites
           *   const foo = goog.require('bar.foo');
           * to
           *   goog.require('bar.foo');
           *   const foo = bar.foo;
           */
          private void visitRequire(Node requireCall, Node parent) {
            String namespace = requireCall.getLastChild().getString();
            if (!parent.getParent().isConst()) {
              compiler.report(JSError.make(parent.getParent(), LHS_OF_GOOG_REQUIRE_MUST_BE_CONST));
            }

            // If the LHS is a destructuring pattern with the "shorthand" syntax,
            // desugar it because otherwise the renaming will not be done correctly.
            //   const {x} = goog.require('y')
            // becomes
            //   const {x: x} = goog.require('y');
            if (parent.isObjectPattern()) {
              for (Node key = parent.getFirstChild(); key != null; key = key.getNext()) {
                if (!key.hasChildren()) {
                  key.addChildToBack(IR.name(key.getString()).useSourceInfoFrom(key));
                }
              }
            }

            Node replacement = NodeUtil.newQName(compiler, namespace).srcrefTree(requireCall);
            parent.replaceChild(requireCall, replacement);
            Node varNode = parent.getParent();
            varNode.getParent().addChildBefore(
                IR.exprResult(requireCall).srcrefTree(requireCall),
                varNode);
          }
        });
  }

  /**
   * Traverses a node tree and
   * <ol>
   *   <li>Appends a suffix to all global variable names defined in this module.
   *   <li>Changes references to imported values to be property accesses on the
   *    imported module object.
   * </ol>
   */
  private class RenameGlobalVars extends AbstractPostOrderCallback {
    private final String suffix;

    RenameGlobalVars(String suffix) {
      this.suffix = suffix;
    }

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      JSDocInfo info = n.getJSDocInfo();
      if (info != null) {
        for (Node typeNode : info.getTypeNodes()) {
          fixTypeNode(t, typeNode);
        }
      }

      boolean isShorthandObjLitKey = n.isStringKey() && !n.hasChildren();
      if (n.isName() || isShorthandObjLitKey) {
        String name = n.getString();
        if (suffix.equals(name)) {
          // TODO(moz): Investigate whether we need to return early in this unlikely situation.
          return;
        }

        Var var = t.getScope().getVar(name);
        if (var != null && var.isGlobal()) {
          // Avoid polluting the global namespace.
          String newName = name + "$$" + suffix;
          if (isShorthandObjLitKey) {
            // Change {a} to {a: a$$module$foo}
            n.addChildToBack(IR.name(newName).useSourceInfoIfMissingFrom(n));
          } else {
            n.setString(newName);
            n.setOriginalName(name);
          }
          t.reportCodeChange(n);
        } else if (var == null && importMap.containsKey(name)) {
          // Change to property access on the imported module object.
          if (parent.isCall() && parent.getFirstChild() == n) {
            parent.putBooleanProp(Node.FREE_CALL, false);
          }

          ModuleOriginalNamePair pair = importMap.get(name);
          Node moduleAccess = NodeUtil.newQName(compiler, pair.module);
          if (pair.originalName.isEmpty()) {
            n.replaceWith(moduleAccess.useSourceInfoIfMissingFromForTree(n));
          } else {
            n.replaceWith(
                IR.getprop(moduleAccess, IR.string(pair.originalName))
                    .useSourceInfoIfMissingFromForTree(n));
          }
          t.reportCodeChange(moduleAccess);
        }
      }
    }

    /**
     * Replace type name references. Change short names to fully qualified names
     * with namespace prefixes. Eg: {Foo} becomes {module$test.Foo}.
     */
    private void fixTypeNode(NodeTraversal t, Node typeNode) {
      if (typeNode.isString()) {
        String name = typeNode.getString();
        if (ModuleLoader.isPathIdentifier(name)) {
          int lastSlash = name.lastIndexOf('/');
          int endIndex = name.indexOf('.', lastSlash);
          String localTypeName = null;
          if (endIndex == -1) {
            endIndex = name.length();
          } else {
            localTypeName = name.substring(endIndex);
          }

          String moduleName = name.substring(0, endIndex);
          ModuleLoader.ModulePath path =
              t.getInput()
                  .getPath()
                  .resolveJsModule(
                      moduleName,
                      typeNode.getSourceFileName(),
                      typeNode.getLineno(),
                      typeNode.getCharno());

          if (path == null) {
            path = t.getInput().getPath().resolveModuleAsPath(moduleName);
          }
          String globalModuleName = path.toModuleName();

          typeNode.setString(
              localTypeName == null ? globalModuleName : globalModuleName + localTypeName);
        } else {
          List<String> splitted = Splitter.on('.').limit(2).splitToList(name);
          String baseName = splitted.get(0);
          String rest = "";
          if (splitted.size() == 2) {
            rest = "." + splitted.get(1);
          }
          Var var = t.getScope().getVar(baseName);
          if (var != null && var.isGlobal()) {
            typeNode.setString(baseName + "$$" + suffix + rest);
          } else if (var == null && importMap.containsKey(baseName)) {
            ModuleOriginalNamePair pair = importMap.get(baseName);
            if (pair.originalName.isEmpty()) {
              typeNode.setString(pair.module + rest);
            } else {
              typeNode.setString(baseName + "$$" + pair.module + rest);
            }
          }
          typeNode.setOriginalName(name);
        }
      }

      for (Node child = typeNode.getFirstChild(); child != null;
           child = child.getNext()) {
        fixTypeNode(t, child);
      }
      t.reportCodeChange();
    }
  }

  private static class ModuleOriginalNamePair {
    private String module;
    private String originalName;

    private ModuleOriginalNamePair(String module, String originalName) {
      this.module = module;
      this.originalName = originalName;
    }

    @Override
    public String toString() {
      return "(" + module + ", " + originalName + ")";
    }
  }

  private static class NameNodePair {
    final String name;
    final Node nodeForSourceInfo;

    private NameNodePair(String name, Node nodeForSourceInfo) {
      this.name = name;
      this.nodeForSourceInfo = nodeForSourceInfo;
    }

    @Override
    public String toString() {
      return "(" + name + ", " + nodeForSourceInfo + ")";
    }
  }
}