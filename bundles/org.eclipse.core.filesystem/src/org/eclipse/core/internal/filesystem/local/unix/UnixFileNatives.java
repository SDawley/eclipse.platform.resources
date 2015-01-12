/*******************************************************************************
 * Copyright (c) 2010, 2015 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Sergey Prigogin (Google) - [338010] Resource.createLink() does not preserve symbolic links
 *******************************************************************************/
package org.eclipse.core.internal.filesystem.local.unix;

import java.io.*;
import java.util.Enumeration;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileInfo;
import org.eclipse.core.filesystem.provider.FileInfo;
import org.eclipse.core.internal.filesystem.*;
import org.eclipse.core.internal.filesystem.local.Convert;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.osgi.util.NLS;

public abstract class UnixFileNatives {

	private static final String LIBRARY_NAME = "unixfile_1_0_0"; //$NON-NLS-1$
	private static final int UNICODE_SUPPORTED = 1 << 0;
	private static final int CHFLAGS_SUPPORTED = 1 << 1;
	private static final int ENOENT = 2; // errno value for "No such file or directory"

	private static final boolean usingNatives;
	private static final int libattr;

	static {
		boolean _usingNatives = false;
		int _libattr = 0;
		try {
			System.loadLibrary(LIBRARY_NAME);
			_usingNatives = true;
			_libattr = libattr();
		} catch (UnsatisfiedLinkError e) {
			if (isLibraryPresent())
				logMissingNativeLibrary(e);
		} finally {
			usingNatives = _usingNatives;
			libattr = _libattr;
		}
	}

	private static boolean isLibraryPresent() {
		String libName = System.mapLibraryName(LIBRARY_NAME);
		Enumeration entries = Activator.findEntries("/", libName, true); //$NON-NLS-1$
		return entries != null && entries.hasMoreElements();
	}

	private static void logMissingNativeLibrary(UnsatisfiedLinkError e) {
		String libName = System.mapLibraryName(LIBRARY_NAME);
		String message = NLS.bind(Messages.couldNotLoadLibrary, libName);
		Policy.log(IStatus.INFO, message, e);
	}

	public static int getSupportedAttributes() {
		if (!usingNatives)
			return -1;
		int ret = EFS.ATTRIBUTE_READ_ONLY | EFS.ATTRIBUTE_EXECUTABLE | EFS.ATTRIBUTE_SYMLINK | EFS.ATTRIBUTE_LINK_TARGET | EFS.ATTRIBUTE_OWNER_READ | EFS.ATTRIBUTE_OWNER_WRITE | EFS.ATTRIBUTE_OWNER_EXECUTE | EFS.ATTRIBUTE_GROUP_READ | EFS.ATTRIBUTE_GROUP_WRITE | EFS.ATTRIBUTE_GROUP_EXECUTE | EFS.ATTRIBUTE_OTHER_READ | EFS.ATTRIBUTE_OTHER_WRITE | EFS.ATTRIBUTE_OTHER_EXECUTE;
		if (isSupported(CHFLAGS_SUPPORTED))
			ret |= EFS.ATTRIBUTE_IMMUTABLE;
		return ret;
	}

	public static FileInfo fetchFileInfo(String fileName) {
		FileInfo info = null;
		byte[] name = fileNameToBytes(fileName);
		StructStat stat = new StructStat();
		if (lstat(name, stat) == 0) {
			if ((stat.st_mode & UnixFileFlags.S_IFMT) == UnixFileFlags.S_IFLNK) {
				if (stat(name, stat) == 0) {
					info = stat.toFileInfo();
				} else {
					info = new FileInfo();
					if (getErrno() != ENOENT)
						info.setError(IFileInfo.IO_ERROR);
				}
				info.setAttribute(EFS.ATTRIBUTE_SYMLINK, true);
				byte target[] = new byte[UnixFileFlags.PATH_MAX];
				int length = readlink(name, target, target.length);
				if (length > 0)
					info.setStringAttribute(EFS.ATTRIBUTE_LINK_TARGET, bytesToFileName(target, length));
			} else
				info = stat.toFileInfo();
		} else {
			info = new FileInfo();
			if (getErrno() != ENOENT)
				info.setError(IFileInfo.IO_ERROR);
		}

		// Fill in the real name of the file.
		File file = new File(fileName);
		final String lastName = file.getName();
		// If the file does not exist, or the file system is not case sensitive, or the name
		// of the file is not case sensitive, use the name we have. Otherwise obtain the real
		// name of the file from a parent directory listing.
		if (!info.exists() || EFS.getLocalFileSystem().isCaseSensitive() ||
				lastName.toLowerCase().equals(lastName.toUpperCase())) {
			info.setName(lastName);
		} else {
			// Notice that file.getParentFile() is guaranteed to be not null since fileName == "/"
			// case is handled by the other branch of the 'if' statement.
			String[] names = file.getParentFile().list(new FilenameFilter() {
				public boolean accept(File dir, String n) {
					return n.equalsIgnoreCase(lastName);
				}
			});
			if (names.length == 1) {
				info.setName(names[0]);
			} else {
				info.setName(lastName);
			}
		}

		return info;
	}

	public static boolean putFileInfo(String fileName, IFileInfo info, int options) {
		int code = 0;
		byte[] name = fileNameToBytes(fileName);
		if (name == null)
			return false;

		// In case uchg flag is to be removed do it before calling chmod
		if (!info.getAttribute(EFS.ATTRIBUTE_IMMUTABLE) && isSupported(CHFLAGS_SUPPORTED)) {
			StructStat stat = new StructStat();
			if (stat(name, stat) == 0) {
				long flags = stat.st_flags;
				flags &= ~UnixFileFlags.SF_IMMUTABLE;
				flags &= ~UnixFileFlags.UF_IMMUTABLE;
				code |= chflags(name, (int) flags);
			}
		}

		// Change permissions
		int mode = 0;
		if (info.getAttribute(EFS.ATTRIBUTE_OWNER_READ))
			mode |= UnixFileFlags.S_IRUSR;
		if (info.getAttribute(EFS.ATTRIBUTE_OWNER_WRITE))
			mode |= UnixFileFlags.S_IWUSR;
		if (info.getAttribute(EFS.ATTRIBUTE_OWNER_EXECUTE))
			mode |= UnixFileFlags.S_IXUSR;
		if (info.getAttribute(EFS.ATTRIBUTE_GROUP_READ))
			mode |= UnixFileFlags.S_IRGRP;
		if (info.getAttribute(EFS.ATTRIBUTE_GROUP_WRITE))
			mode |= UnixFileFlags.S_IWGRP;
		if (info.getAttribute(EFS.ATTRIBUTE_GROUP_EXECUTE))
			mode |= UnixFileFlags.S_IXGRP;
		if (info.getAttribute(EFS.ATTRIBUTE_OTHER_READ))
			mode |= UnixFileFlags.S_IROTH;
		if (info.getAttribute(EFS.ATTRIBUTE_OTHER_WRITE))
			mode |= UnixFileFlags.S_IWOTH;
		if (info.getAttribute(EFS.ATTRIBUTE_OTHER_EXECUTE))
			mode |= UnixFileFlags.S_IXOTH;
		code |= chmod(name, mode);

		// In case uchg flag is to be added do it after calling chmod
		if (info.getAttribute(EFS.ATTRIBUTE_IMMUTABLE) && isSupported(CHFLAGS_SUPPORTED)) {
			StructStat stat = new StructStat();
			if (stat(name, stat) == 0) {
				long flags = stat.st_flags;
				flags |= UnixFileFlags.UF_IMMUTABLE;
				code |= chflags(name, (int) flags);
			}
		}
		return code == 0;
	}

	public static boolean isUsingNatives() {
		return usingNatives;
	}

	public static int getErrno() {
		return errno();
	}

	public static int getFlag(String flag) {
		if (!usingNatives)
			return -1;
		try {
			return getflag(flag.getBytes("ASCII")); //$NON-NLS-1$
		} catch (UnsupportedEncodingException e) {
			// Should never happen
			return -1;
		}
	}

	private static byte[] fileNameToBytes(String fileName) {
		if (isSupported(UNICODE_SUPPORTED))
			return tounicode(fileName.toCharArray());
		return Convert.toPlatformBytes(fileName);
	}

	private static String bytesToFileName(byte[] buf, int length) {
		if (isSupported(UNICODE_SUPPORTED))
			return new String(buf, 0, length);
		return Convert.fromPlatformBytes(buf, length);
	}

	private static boolean isSupported(int attr) {
		return (libattr & attr) != 0;
	}

	private static final native int chmod(byte[] path, int mode);

	private static final native int chflags(byte[] path, int flags);

	private static final native int stat(byte[] path, StructStat buf);

	private static final native int lstat(byte[] path, StructStat buf);

	private static final native int readlink(byte[] path, byte[] buf, long bufsiz);

	private static final native int errno();

	private static final native int libattr();

	private static final native byte[] tounicode(char[] buf);

	private static final native int getflag(byte[] buf);

}
