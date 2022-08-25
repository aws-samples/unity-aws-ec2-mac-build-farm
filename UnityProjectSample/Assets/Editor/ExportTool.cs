/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: MIT-0
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

using UnityEditor;
using System.Collections.Generic;

// Put this memu command script into Assets/Editor/

class ExportTool
{
	static void ExportXcodeProject () 
	{
		PlayerSettings.applicationIdentifier = "com.evgeniik.samples.UnityBuildSample";
		EditorUserBuildSettings.SwitchActiveBuildTarget (BuildTarget.iOS);

		EditorUserBuildSettings.symlinkSources = true;
		EditorUserBuildSettings.development = true;
		EditorUserBuildSettings.allowDebugging = true;

		List<string> scenes = new List<string>();
		for (int i = 0; i < EditorBuildSettings.scenes.Length; i++) 
		{
			if (EditorBuildSettings.scenes [i].enabled)
			{
				scenes.Add (EditorBuildSettings.scenes [i].path);
			}
		}

		BuildPipeline.BuildPlayer (scenes.ToArray (), "iOSProj", BuildTarget.iOS, BuildOptions.None);
	}
}
