require 'cgi'
require 'uri'
require 'securerandom'

#
# Keeps screenshots visible in the body of outgoing emails.
#
# Screenshots in notes point at Redmine attachment URLs
# (/attachments/download/12/image.png) or, in helpdesk mails, at bare file
# names (image.png). Mail clients can't load either: one needs a Redmine
# login, the other isn't a URL at all. This module swaps those images for
# inline (cid:) parts carried inside the email itself.
#
# Registered as a mail interceptor, so it covers every Redmine email (issue
# notifications, helpdesk replies, ...) whatever the delivery method. Only
# attachments of the mail's own issue/project (X-Redmine-* headers) are
# embedded, so a note can't pull in files from elsewhere.
#
module HelpdeskInlineImages
  MAX_IMAGE_BYTES = 10 * 1024 * 1024
  MAX_TOTAL_BYTES = 20 * 1024 * 1024

  # <img ... src="..."> / src='...'
  IMG_SRC_RE = /(<img\b[^>]*?\bsrc\s*=\s*)(["'])(.*?)\2/im
  # /attachments/12, /attachments/download/12/x.png, /attachments/thumbnail/12/200
  ATTACHMENT_PATH_RE = %r{/attachments/(?:download/|thumbnail/)?(\d+)(?:/|\z)}

  # Mail interceptor hook
  def self.delivering_email(message)
    embed_in_message(message)
  rescue => e
    Rails.logger.error "Helpdesk: could not embed inline images: #{e.class}: #{e.message}"
  end

  # Rewrites the html part of +message+ so attachment screenshots are inline.
  def self.embed_in_message(message)
    return unless message.multipart?
    html_part = message.html_part
    return if html_part.nil?

    issue, project = context_for(message)
    return if issue.nil? && project.nil?

    cids = {}
    html = rewrite_images(html_part.decoded.to_s, issue: issue, project: project) do |attachment|
      cids[attachment.id] ||= "#{SecureRandom.hex(12)}@#{cid_domain}"
    end
    return if cids.empty?

    related = Mail::Part.new
    related.content_type = 'multipart/related'
    new_html = Mail::Part.new
    new_html.content_type = 'text/html; charset=UTF-8'
    new_html.body = html
    related.add_part(new_html)
    Attachment.where(id: cids.keys).each do |a|
      related.attachments.inline[a.filename] = {
        :content => File.binread(a.diskfile),
        :content_id => "<#{cids[a.id]}>"
      }
    end
    replace_part(message, html_part, related)
    # Mail caches the html part when it was built with html_part { }; drop
    # it so html_part finds the new one
    message.instance_variable_set(:@html_part, nil) if message.instance_variable_defined?(:@html_part)
  end

  # Replaces the src of every <img> in +html+ that resolves to an embeddable
  # attachment with the cid the block returns for it. Stops embedding once
  # MAX_TOTAL_BYTES is reached (later images keep their original src).
  def self.rewrite_images(html, issue: nil, project: nil)
    seen = {}
    total = 0
    html.gsub(IMG_SRC_RE) do
      m = Regexp.last_match
      a = attachment_for(CGI.unescapeHTML(m[3]), issue: issue, project: project)
      if a && !seen[a.id] && total + a.filesize.to_i > MAX_TOTAL_BYTES
        a = nil
      end
      if a
        unless seen[a.id]
          seen[a.id] = true
          total += a.filesize.to_i
        end
        cid = yield(a).to_s.sub(/\Acid:/, '')
        "#{m[1]}#{m[2]}cid:#{cid}#{m[2]}"
      else
        m[0]
      end
    end
  end

  # Returns the attachment an image +src+ points to, or nil when it isn't an
  # embeddable image of +issue+'s project (or +project+).
  def self.attachment_for(src, issue: nil, project: nil)
    src = src.to_s.strip
    return nil if src.empty? || src =~ /\A(cid|data):/i

    a = nil
    if (id = attachment_id_from_url(src))
      a = Attachment.find_by(id: id)
      a = nil unless a && allowed?(a, issue, project)
    elsif issue && src !~ %r{[/:]}
      # bare file name, as rendered from !image.png! without attachment context
      name = unescape(src).downcase
      a = issue.attachments.select { |x| x.filename.to_s.downcase == name }.max_by(&:id)
    end
    a if a && embeddable?(a)
  end

  def self.attachment_id_from_url(src)
    uri = URI.parse(src)
    return nil if uri.host && !own_host?(uri)
    return nil if uri.host.nil? && uri.scheme
    m = uri.path.to_s.match(ATTACHMENT_PATH_RE)
    m && m[1].to_i
  rescue URI::InvalidURIError
    nil
  end

  def self.own_host?(uri)
    expected = Setting.host_name.to_s.strip.downcase.sub(%r{\A[a-z]+://}, '').sub(%r{/.*\z}, '')
    return false if expected.empty?
    actual = uri.host.to_s.downcase
    actual = "#{actual}:#{uri.port}" if uri.port && uri.port != uri.default_port
    actual == expected || (expected !~ /:\d+\z/ && uri.host.to_s.downcase == expected)
  end

  def self.allowed?(attachment, issue, project)
    project ||= issue.project if issue
    return false if project.nil?
    attachment.project.present? && attachment.project.id == project.id
  end

  def self.embeddable?(attachment)
    attachment.image? &&
      attachment.filesize.to_i <= MAX_IMAGE_BYTES &&
      File.readable?(attachment.diskfile)
  end

  def self.context_for(message)
    issue_id = message.header['X-Redmine-Issue-Id'].to_s
    issue = Issue.find_by(id: issue_id.to_i) if issue_id =~ /\A\d+\z/
    project = issue.project if issue
    if project.nil?
      identifier = message.header['X-Redmine-Project'].to_s
      project = Project.find_by(identifier: identifier) if identifier.present?
    end
    [issue, project]
  end

  def self.replace_part(container, old_part, new_part)
    container.parts.each_with_index do |part, i|
      if part.equal?(old_part)
        container.parts[i] = new_part
        return true
      end
      return true if part.multipart? && replace_part(part, old_part, new_part)
    end
    false
  end

  def self.cid_domain
    host = Setting.host_name.to_s.sub(%r{\A[a-z]+://}i, '').sub(%r{[:/].*\z}, '')
    host.present? ? host : 'redmine.local'
  end

  def self.unescape(s)
    s.b.gsub(/%([0-9a-f]{2})/i) { $1.hex.chr }.force_encoding('UTF-8')
  end
end
