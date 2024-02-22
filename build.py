#!/usr/bin/env python3
import importlib, subprocess, os

def git_lib(path):
  if os.path.exists(path): return path
  path2 = path+"Clone"
  print("using "+path2+" submodule; link custom path to "+path+" to override")
  subprocess.check_call(["git","submodule","update","--init",path2])
  return path2

uiPath = git_lib("UI")
b = importlib.import_module(uiPath+".build")

cp = b.build_ui_lib(uiPath)
cp+= [b.maven_lib("org/jetbrains/pty4j", "pty4j", "0.12.13", "lib", "8cae0b647c734dcd3ed5974bb596518f351d752e335a60084fcebab5da108c0d")]
cp+= [b.maven_lib("org/jetbrains/pty4j", "purejavacomm", "0.0.11.1", "lib", "31c048a86057e07272429aa26e90713edbde96af9747362f27470d8e86a398a3", repo = "https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")]
cp+= [b.maven_lib("net/java/dev/jna", "jna", "5.12.1", "lib", "91a814ac4f40d60dee91d842e1a8ad874c62197984403d0e3c30d39e55cf53b3")]
cp+= [b.maven_lib("net/java/dev/jna", "jna", "5.12.1", "lib", "033e8ac7a742624748bf251648f15096cf6e15abe0c4c15f4a77ca10816a1324", "-jpms")]
b.jar("grr.jar", cp)
b.make_run("run", cp+["grr.jar"], "dz.Main", "-ea")