/**
 * Copyright (c) 2011, 2014 Eurotech and/or its affiliates
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Eurotech
 */
/*
* Copyright (c) 2011 Eurotech Inc. All rights reserved.
*/

package org.eclipse.kura.linux.net.dns;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

import org.eclipse.kura.KuraErrorCode;
import org.eclipse.kura.KuraException;
import org.eclipse.kura.core.util.ProcessUtil;
import org.eclipse.kura.core.util.SafeProcess;
import org.eclipse.kura.net.IP4Address;
import org.eclipse.kura.net.IPAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class LinuxDns {

	private static Logger s_logger = LoggerFactory.getLogger(LinuxDns.class);
	
	private static final String DNS_FILE_NAME = "/etc/resolv.conf";
	//private static final String PPP_DNS_FILE_NAME = "/etc/ppp/resolv.conf";
	private static final String [] PPP_DNS_FILES = {"/var/run/ppp/resolv.conf", "/etc/ppp/resolv.conf"};
	private static final String BACKUP_DNS_FILE_NAME = "/etc/resolv.conf.save";
	
	private static Object s_lock;
	private static LinuxDns s_linuxDns = null;
	
	private LinuxDns() {
		s_lock = new Object();
	}
	
	public static synchronized LinuxDns getInstance() {
		if(s_linuxDns == null) {
			s_linuxDns = new LinuxDns();
		}
		
		return s_linuxDns;
	}

	
	public Set<IPAddress> getDnServers() {
		synchronized(s_lock) {
			BufferedReader br = null;
			Set<IPAddress> servers = new HashSet<IPAddress>();
			
			try {
				File f = new File(DNS_FILE_NAME);
				if(!f.exists()) {
					f.createNewFile();
				}
				
				br = new BufferedReader(new FileReader(new File(DNS_FILE_NAME)));
				
				String line = null;
				while((line = br.readLine()) != null) {
					line = line.trim();
					if(line.indexOf("nameserver") == 0) {
						StringTokenizer st = new StringTokenizer(line);
						st.nextToken();
						servers.add(IPAddress.parseHostAddress(st.nextToken()));
					}
				}
				
				if(servers.size() > 0) {
					return servers;
				} else {
					s_logger.debug("No DNS servers found");
					return null;
				}
			} catch (Exception e) {
				e.printStackTrace();
				return null;
			} finally {
				if (br != null) {
					try {
						br.close();
						br = null;
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		}
	}
	
	public void setDnServers(Set<IPAddress> servers) {
		if(servers == null) {
			if(getDnServers() != null) {
				writeDnsFile(new HashSet<IPAddress>());
			}
		} else if(!servers.equals(getDnServers())) {
			writeDnsFile(servers);
		}
	}
	
	public List<IPAddress> getPppDnServers() throws KuraException {
		synchronized(s_lock) {
			BufferedReader br = null;
			ArrayList<IPAddress> serversList = new ArrayList<IPAddress>();
			try {
				String pppDnsFileName = getPppDnsFileName();
				 if (pppDnsFileName != null) {
					File pppDnsFile = new File(pppDnsFileName);
					br = new BufferedReader(new FileReader(pppDnsFile));
					String line;
					while((line = br.readLine()) != null) {
						line = line.trim();
						if(line.indexOf("nameserver") == 0) {
							StringTokenizer st = new StringTokenizer(line);
							st.nextToken();
							serversList.add(IP4Address.parseHostAddress(st.nextToken()));
						}
					}					
			    }
			} catch (Exception e) {
				throw new KuraException(KuraErrorCode.INTERNAL_ERROR, e);
			} finally {
				if(br != null) {
					try {
						br.close();
						br = null;
					} catch (IOException e) {
						throw new KuraException(KuraErrorCode.INTERNAL_ERROR, e);
					}
				}
			}
			return serversList;
		}
	}
	
	public List<IPAddress> getDhcpDnsServers(String interfaceName, String address) throws KuraException {
		IPAddress ipAddress = null;
		try {
			ipAddress = IP4Address.parseHostAddress(address);
		} catch (UnknownHostException e) {
			s_logger.error("Error parsing ip address " + address, e);
			throw new KuraException(KuraErrorCode.INTERNAL_ERROR, e);
		}
		return getDhcpDnsServers(interfaceName, ipAddress);
	}
	
	public List<IPAddress> getDhcpDnsServers(String interfaceName, IPAddress address) throws KuraException {
		BufferedReader br = null;
		ArrayList<IPAddress> servers = null;
		
		if(interfaceName != null && !interfaceName.isEmpty() && address != null && address.getAddress() != null) {
			
			StringBuilder sb = new StringBuilder();
			sb.append("interface \"")
			.append(interfaceName)
			.append("\";");
			String interfaceMatch = sb.toString();
			
			sb = new StringBuilder();
			sb.append("fixed-address ")
			.append(address.getHostAddress())
			.append(";");
			String fixedAddressMatch = sb.toString();
			
			File globalDhClientFile = new File("/var/lib/dhcp/dhclient.leases");
			File interfaceDhClientFile = new File("/var/lib/dhcp/dhclient." + interfaceName + ".leases");
			
			if (interfaceDhClientFile.exists()) {
				try {
					br = new BufferedReader(new FileReader(interfaceDhClientFile));
	    			
					String line = null;
					while((line = br.readLine()) != null) {
						if(line.trim().equals("lease {")) {
	    					//make sure it matches
	    					if(!br.readLine().trim().equals(interfaceMatch)) {
	    						continue;
	    					}
	    					if(!br.readLine().trim().equals(fixedAddressMatch)) {
	    						continue;
	    					}
	    					while((line = br.readLine()) != null) {
	    						if(line.indexOf("domain-name-servers") >= 0) {
	    							StringTokenizer st = new StringTokenizer(line.substring(line.indexOf("domain-name-servers") + 19), ", ;");
	    							if (servers == null) {
	    								servers = new ArrayList<IPAddress>();
	    							}
	    							while(st.hasMoreTokens()) {
	    								String nameServer = st.nextToken();
	    								s_logger.debug("Found nameserver... " + nameServer); 
	    								IPAddress ipa = IPAddress.parseHostAddress(nameServer);
	    								if (!servers.contains(ipa)) {
	    								servers.add(IPAddress.parseHostAddress(nameServer));
	    								}
	    							}
	    							break;
	    						} else if(line.indexOf("expire") >= 0) {
	    							//no DNS for this entry
	    							break;
	    						}
	    					}
	    				}
					}
				} catch (Exception e) {
					throw new KuraException (KuraErrorCode.INTERNAL_ERROR, e);
				} finally {
					if (br != null) {
						try {
							br.close();
							br = null;
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
			} else if(globalDhClientFile.exists()) {
				try {
					br = new BufferedReader(new FileReader(globalDhClientFile));
	    			
					String line = null;
					while((line = br.readLine()) != null) {
						if(line.trim().equals("lease {")) {
	    					//make sure it matches
	    					if(!br.readLine().trim().equals(interfaceMatch)) {
	    						continue;
	    					}
	    					if(!br.readLine().trim().equals(fixedAddressMatch)) {
	    						continue;
	    					}
	    					while((line = br.readLine()) != null) {
	    						if(line.indexOf("domain-name-servers") >= 0) {
	    							StringTokenizer st = new StringTokenizer(line.substring(line.indexOf("domain-name-servers") + 19), ", ;");
	    							servers = new ArrayList<IPAddress>();
	    							while(st.hasMoreTokens()) {
	    								String nameServer = st.nextToken();
	    								s_logger.debug("Found nameserver... " + nameServer); 
	    								servers.add(IPAddress.parseHostAddress(nameServer));
	    							}
	    							break;
	    						} else if(line.indexOf("expire") >= 0) {
	    							//no DNS for this entry
	    							break;
	    						}
	    					}
	    				}
					}
				} catch (Exception e) {
					throw new KuraException (KuraErrorCode.INTERNAL_ERROR, e);
				} finally {
					if (br != null) {
						try {
							br.close();
							br = null;
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
				
			} else {
	    		sb = new StringBuilder();
	    		sb.append("/var/lib/dhclient/dhclient-")
	    		.append(interfaceName)
	    		.append(".leases");
	    
	    		try {
	    		    File dhclientFile = new File(sb.toString());
	    		    if(!dhclientFile.exists()) {
	    		        dhclientFile.createNewFile();
	    		    }
	    			br = new BufferedReader(new FileReader(dhclientFile));
	    			
	    			//boolean lookingForDns = false;
	    			String line;
	    			while((line = br.readLine()) != null) {
	    				if(line.trim().equals("lease {")) {
	    					//make sure it matches
	    					if(!br.readLine().trim().equals(interfaceMatch)) {
	    						continue;
	    					}
	    					if(!br.readLine().trim().equals(fixedAddressMatch)) {
	    						continue;
	    					}
	    					while((line = br.readLine()) != null) {
	    						if(line.indexOf("domain-name-servers") >= 0) {
	    							StringTokenizer st = new StringTokenizer(line.substring(line.indexOf("domain-name-servers") + 19), ", ;");
	    							servers = new ArrayList<IPAddress>();
	    							while(st.hasMoreTokens()) {
	    								String nameServer = st.nextToken();
	    								s_logger.debug("Found nameserver... " + nameServer); 
	    								servers.add(IPAddress.parseHostAddress(nameServer));
	    							}
	    							break;
	    						} else if(line.indexOf("expire") >= 0) {
	    							//no DNS for this entry
	    							break;
	    						}
	    					}
	    				}
	    			}
	    		} catch (Exception e) {
	    			throw new KuraException (KuraErrorCode.INTERNAL_ERROR, e);
	    		} finally {
	    			if (br != null) {
	    				try {
							br.close();
							br = null;
						} catch (IOException e) {
							e.printStackTrace();
						}
	    			}
	    		}
			}
		}
		
		return servers;
	}

	public void removeDnsServer(IPAddress serverIpAddress) {
		synchronized(s_lock) {
			try {
				if(isPppDnsSet()) {
					return;
				}
			} catch(Exception e) {
				e.printStackTrace();
			}
			
			Set<IPAddress> servers = getDnServers();
			Set<IPAddress> newServers = new HashSet<IPAddress>();
			
			for(IPAddress server : servers) {
				if(server.equals(serverIpAddress)) {
					s_logger.info("removed the DNS server: " + serverIpAddress);
				} else {
					//keep it for the new Dns file
					newServers.add(server);
				}
			}
			
			writeDnsFile(newServers);
		}
	}

	public void addDnsServer(IPAddress serverIpAddress) {
		synchronized(s_lock) {
			try {
				if(isPppDnsSet()) {
					return;
				}
			} catch(Exception e) {
				e.printStackTrace();
			}
			
			Set<IPAddress> servers = getDnServers();
			
			if (servers == null) {
				servers = new HashSet<IPAddress>();
			}
			servers.add(serverIpAddress);
			
			writeDnsFile(servers);
		}
	}

	public void setPppDns() throws Exception {
		synchronized(s_lock) {
			String sPppDnsFileName = getPppDnsFileName();
			if (sPppDnsFileName != null) {
				if(!isPppDnsSet()) {
					File file = new File(DNS_FILE_NAME);
					if(file.exists()) {
						SafeProcess proc = null;
						try {
							proc = ProcessUtil.exec("mv " + DNS_FILE_NAME + " " + BACKUP_DNS_FILE_NAME);
							if(proc.waitFor() != 0) {
								s_logger.error("failed to backup " + DNS_FILE_NAME);
								throw new Exception("Failed to backup " + DNS_FILE_NAME);
							} else {
								s_logger.info("successfully backed up " + BACKUP_DNS_FILE_NAME);
							}
						}
						finally {
							if (proc != null) ProcessUtil.destroy(proc);
						}
					}
					
					SafeProcess proc = null;
					try {
						proc = ProcessUtil.exec("ln -sf " + sPppDnsFileName + " " + DNS_FILE_NAME);
						if(proc.waitFor() != 0) {
							s_logger.error("failed to link " + DNS_FILE_NAME + " to " + sPppDnsFileName);
							throw new Exception("Failed to backup " + DNS_FILE_NAME);
						} else {
							s_logger.info("set DNS to use ppp resolv.conf");
						}
					}
					finally {
						if (proc != null) ProcessUtil.destroy(proc);
					}
				}
			}
		}
	}

	public void unsetPppDns() throws Exception {
		synchronized(s_lock) {
			if(isPppDnsSet()) {
				String pppDnsFilename = getPppDnsFileName();
				File file = new File(DNS_FILE_NAME);
				if(file.exists()) {
					SafeProcess proc = null;
					try {
						proc = ProcessUtil.exec("rm " + DNS_FILE_NAME);
						if(proc.waitFor() != 0) {
							s_logger.error("failed to delete " + DNS_FILE_NAME + " symlink that points to " + pppDnsFilename);
							throw new Exception("failed to delete " + DNS_FILE_NAME + " symlink that points to " + pppDnsFilename);
						} else {
							s_logger.info("successfully removed symlink that points to " + pppDnsFilename);
						}
					}
					finally {
						if (proc != null) ProcessUtil.destroy(proc);
					}
				}
				
				file = new File(BACKUP_DNS_FILE_NAME);
				if(file.exists()) {
					SafeProcess proc = null;
					try {
						proc = ProcessUtil.exec("mv " + BACKUP_DNS_FILE_NAME + " " + DNS_FILE_NAME);
						if(proc.waitFor() != 0) {
							s_logger.error("failed to restore " + BACKUP_DNS_FILE_NAME + " to " + DNS_FILE_NAME);
							throw new Exception("failed to restore " + BACKUP_DNS_FILE_NAME + " to " + DNS_FILE_NAME);
						} else {
							s_logger.info("successfully restored " + DNS_FILE_NAME + " from " + BACKUP_DNS_FILE_NAME);
						}
					}
					finally {
						if (proc != null) ProcessUtil.destroy(proc);
					}						
				} else {
					SafeProcess proc = null;
					try {
						proc = ProcessUtil.exec("touch " + DNS_FILE_NAME);
						if(proc.waitFor() != 0) {
							s_logger.error("failed to create empty " + DNS_FILE_NAME);
							throw new Exception("failed to create empty " + DNS_FILE_NAME);
						} else {
							s_logger.info("successfully created empty " + DNS_FILE_NAME);
						}
					}
					finally {
						if (proc != null) ProcessUtil.destroy(proc);
					}
				}
				
				// remove actual PPP DNS file
				File pppDnsFile = new File (pppDnsFilename);
				if(pppDnsFile.exists()) {
					pppDnsFile.delete();
				}
			}
		}
	}
	
	public boolean isPppDnsSet() throws Exception {
		synchronized(s_lock) {
			File file = new File(DNS_FILE_NAME);
			if(isSymlink(file)) {
				if(getRealPath(file).compareTo(getPppDnsFileName()) == 0) {
					return true;
				} else {
					return false;
				}
			} else {
				return false;
			}
		}
	}
	
    private boolean isSymlink(File file) throws IOException {
    	synchronized(s_lock) {
	        if (file == null) {
	        	throw new NullPointerException("File must not be null");
	        }
	
	        File canon;
	        if (file.getParent() == null) {
	                canon = file;
	        } else {
	                File canonDir = file.getParentFile().getCanonicalFile();
	                canon = new File(canonDir, file.getName());
	        }
	
	        return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
    	}
    }

    private String getRealPath(File file) throws IOException {
    	synchronized(s_lock) {
	        if (file == null) {
	        	throw new NullPointerException("File must not be null");
	        }
	
	        File canon;
	        if (file.getParent() == null) {
	                canon = file;
	        } else {
	                File canonDir = file.getParentFile().getCanonicalFile();
	                canon = new File(canonDir, file.getName());
	        }
	        
	        return canon.getCanonicalFile().toString();
    	}
    }
    
    private void writeDnsFile(Set<IPAddress> servers) {
		s_logger.debug("Writing DNS servers to file");
		synchronized(s_lock) {
			try {
				FileOutputStream fos = new FileOutputStream(DNS_FILE_NAME);
				PrintWriter pw = new PrintWriter(fos);
				
				String[] existingFile = getModifiedFile();
				for(int i=0; i<existingFile.length; i++) {
					pw.write(existingFile[i] + "\n");
				}
				pw.write("\n");
				
				for(IPAddress server : servers) {
					pw.write("nameserver " + server.getHostAddress() + "\n");
				}
				
				if(pw != null) {
					pw.flush();
					fos.getFD().sync();
					pw.close();
					fos.close();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

    private String[] getModifiedFile() {
		synchronized(s_lock) {
			BufferedReader br = null;
			try {
			    File dnsFile = new File(DNS_FILE_NAME);
			    if(!dnsFile.exists()) {
			        dnsFile.createNewFile();
			    }
			    
				ArrayList<String> linesWithoutServers = new ArrayList<String>();
				br = new BufferedReader(new FileReader(dnsFile));
				
				String line;
				while((line = br.readLine()) != null) {
					line = line.trim();
					if(line.indexOf("nameserver") != 0) {
						linesWithoutServers.add(line);
					}
				}
				br.close();
				
				String[] lines = new String[linesWithoutServers.size()];
				for(int i=0; i<linesWithoutServers.size(); i++) {
					lines[i] = (String) linesWithoutServers.get(i);
				}
				return lines;
			} catch (Exception e) {
				e.printStackTrace();
			}
			
			if(br != null) {
				try {
					br.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			br = null;
			
			return null;
		}
	}
    
    private String getPppDnsFileName() {
    	
    	String pppDnsFileName = null;
    	for (String name : PPP_DNS_FILES) {
    		File file = new File (name);
    		if (file.exists()) {
    			pppDnsFileName = name;
    			break;
    		}
    	}
    	
    	return pppDnsFileName;
    }
}
